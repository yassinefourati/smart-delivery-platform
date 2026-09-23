#!/usr/bin/env bash
# =============================================================================
# Creates an immutable, encrypted, replicated S3 bucket for PostgreSQL backups
# (Phase 22, ADR 014) and prints the IAM policy the backup role needs.
#
# Why each property:
#   Object Lock, COMPLIANCE  An object cannot be deleted or overwritten before its
#                            retention ends -- not by us, not by the root account, not
#                            by an attacker holding either. Ransomware's first move is
#                            the backups; this is what makes that move fail.
#   versioning               Required by Object Lock; overwrites become new versions.
#   SSE-KMS (bucket key)     Encrypted at rest with a customer-managed key, whose own
#                            policy is a second, independent gate on reading backups.
#   public access block      All four settings; there is no reason this is ever public.
#   deny non-TLS             Bucket policy: every request must use TLS.
#   lifecycle                Expires objects after the retention window, so storage
#                            does not grow forever (the lock just delays the delete).
#   replication (optional)   Cross-region copy for regional disasters -- the DR
#                            region's CNPG replica clusters read from it.
#
# DRY RUN BY DEFAULT: prints every AWS CLI call. Pass --apply to execute.
#
#   ./create-backup-bucket.sh --bucket acme-sdp-pg-backups --region eu-west-1 \
#       --kms-key-arn arn:aws:kms:eu-west-1:111122223333:key/... [--retention-days 30] \
#       [--replicate-to-bucket acme-sdp-pg-backups-dr-replica \
#        --replication-role-arn arn:aws:iam::111122223333:role/sdp-backup-replication] \
#       [--apply]
#
# Object Lock COMPLIANCE is irreversible for the objects it covers. Try it on a
# scratch bucket with --retention-days 1 first.
# =============================================================================
set -euo pipefail

BUCKET="" REGION="" KMS_KEY_ARN="" RETENTION_DAYS=30
REPLICA_BUCKET="" REPLICATION_ROLE_ARN="" APPLY=false

usage() { sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//' | head -n -1; exit "${1:-0}"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bucket) BUCKET="$2"; shift 2 ;;
    --region) REGION="$2"; shift 2 ;;
    --kms-key-arn) KMS_KEY_ARN="$2"; shift 2 ;;
    --retention-days) RETENTION_DAYS="$2"; shift 2 ;;
    --replicate-to-bucket) REPLICA_BUCKET="$2"; shift 2 ;;
    --replication-role-arn) REPLICATION_ROLE_ARN="$2"; shift 2 ;;
    --apply) APPLY=true; shift ;;
    -h|--help) usage 0 ;;
    *) echo "unknown argument: $1" >&2; usage 2 ;;
  esac
done

[[ -n "$BUCKET" && -n "$REGION" && -n "$KMS_KEY_ARN" ]] || { echo "--bucket, --region and --kms-key-arn are required" >&2; exit 2; }
[[ "$RETENTION_DAYS" =~ ^[0-9]+$ && "$RETENTION_DAYS" -ge 1 ]] || { echo "--retention-days must be a positive integer" >&2; exit 2; }
if [[ -n "$REPLICA_BUCKET" && -z "$REPLICATION_ROLE_ARN" ]]; then
  echo "--replicate-to-bucket needs --replication-role-arn" >&2; exit 2
fi

run() {
  if $APPLY; then
    echo "+ $*"; "$@"
  else
    printf '[dry-run]'; printf ' %q' "$@"; echo
  fi
}

# Lifecycle expiry = retention + a small margin, so an object is never expired while
# still locked (S3 would just keep it, but the intent should be explicit).
EXPIRE_DAYS=$((RETENTION_DAYS + 5))

# us-east-1 rejects an explicit LocationConstraint; every other region requires one.
if [[ "$REGION" == "us-east-1" ]]; then
  run aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" --object-lock-enabled-for-bucket
else
  run aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" --object-lock-enabled-for-bucket \
    --create-bucket-configuration "LocationConstraint=$REGION"
fi

run aws s3api put-bucket-versioning --bucket "$BUCKET" --versioning-configuration Status=Enabled

run aws s3api put-object-lock-configuration --bucket "$BUCKET" --object-lock-configuration \
  "{\"ObjectLockEnabled\":\"Enabled\",\"Rule\":{\"DefaultRetention\":{\"Mode\":\"COMPLIANCE\",\"Days\":$RETENTION_DAYS}}}"

run aws s3api put-bucket-encryption --bucket "$BUCKET" --server-side-encryption-configuration \
  "{\"Rules\":[{\"ApplyServerSideEncryptionByDefault\":{\"SSEAlgorithm\":\"aws:kms\",\"KMSMasterKeyID\":\"$KMS_KEY_ARN\"},\"BucketKeyEnabled\":true}]}"

run aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

run aws s3api put-bucket-policy --bucket "$BUCKET" --policy "$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DenyInsecureTransport",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:*",
      "Resource": ["arn:aws:s3:::$BUCKET", "arn:aws:s3:::$BUCKET/*"],
      "Condition": {"Bool": {"aws:SecureTransport": "false"}}
    },
    {
      "Sid": "DenyUnencryptedPuts",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:PutObject",
      "Resource": "arn:aws:s3:::$BUCKET/*",
      "Condition": {"StringNotEquals": {"s3:x-amz-server-side-encryption": "aws:kms"}}
    }
  ]
}
JSON
)"

run aws s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" --lifecycle-configuration \
  "{\"Rules\":[{\"ID\":\"expire-after-retention\",\"Status\":\"Enabled\",\"Filter\":{},\"Expiration\":{\"Days\":$EXPIRE_DAYS},\"NoncurrentVersionExpiration\":{\"NoncurrentDays\":1},\"AbortIncompleteMultipartUpload\":{\"DaysAfterInitiation\":2}}]}"

if [[ -n "$REPLICA_BUCKET" ]]; then
  # The destination must already exist in the DR region, versioned, with its own
  # Object Lock -- replication copies objects, not the source's lock configuration.
  run aws s3api put-bucket-replication --bucket "$BUCKET" --replication-configuration "$(cat <<JSON
{
  "Role": "$REPLICATION_ROLE_ARN",
  "Rules": [{
    "ID": "dr-region",
    "Status": "Enabled",
    "Priority": 1,
    "Filter": {},
    "DeleteMarkerReplication": {"Status": "Disabled"},
    "SourceSelectionCriteria": {"SseKmsEncryptedObjects": {"Status": "Enabled"}},
    "Destination": {
      "Bucket": "arn:aws:s3:::$REPLICA_BUCKET",
      "ReplicationTime": {"Status": "Enabled", "Time": {"Minutes": 15}},
      "Metrics": {"Status": "Enabled", "EventThreshold": {"Minutes": 15}}
    }
  }]
}
JSON
)"
fi

cat <<POLICY

--------------------------------------------------------------------------------
IAM policy for the backup role (IRSA, deploy/helm/sdp-data backup.irsaRoleArn).
Note what is ABSENT: s3:DeleteObject and s3:PutBucket*/PutObjectRetention -- a
compromised database pod can add backups, never remove or unlock them.
--------------------------------------------------------------------------------
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:ListBucket", "s3:GetBucketLocation"],
      "Resource": ["arn:aws:s3:::$BUCKET", "arn:aws:s3:::$BUCKET/*"]
    },
    {
      "Effect": "Allow",
      "Action": ["kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey"],
      "Resource": "$KMS_KEY_ARN"
    }
  ]
}
POLICY
