package com.smartdelivery.order.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims the next batch of publishable rows for the caller's transaction, and only
     * for the caller's transaction (ADR 006). Three things happen in this one statement,
     * and each of them fixes a distinct way the Phase 8 poller misbehaved once a second
     * instance existed:
     *
     * <ul>
     *   <li>{@code FOR UPDATE SKIP LOCKED} makes the claim exclusive. Rows another
     *       instance already claimed are invisible here instead of being published
     *       twice.</li>
     *   <li>The {@code NOT EXISTS} restricts the result to the <em>oldest</em> pending
     *       row per aggregate. That is what keeps one aggregate's events in order: a
     *       later event can never be claimed -- by this instance or any other -- while
     *       an earlier one for the same aggregate is still unpublished, whether it is
     *       unpublished because it is locked by a peer, because it failed, or because
     *       it is backing off.</li>
     *   <li>{@code next_attempt_at} implements the backoff. Note that it is deliberately
     *       <em>not</em> part of the {@code NOT EXISTS}: a backing-off row still blocks
     *       its aggregate's later events, which is the point -- skipping past it would
     *       reorder the stream.</li>
     * </ul>
     *
     * {@code LIMIT} is applied before locking, so a contended table can return fewer
     * than {@code batchSize} rows. That is fine: the next poll picks up the remainder.
     *
     * Written as a native query rather than JPQL because JPQL has no {@code FOR UPDATE
     * SKIP LOCKED}, and paginated via a literal {@code LIMIT} rather than a
     * {@link org.springframework.data.domain.Pageable} because Spring Data appends its
     * own {@code LIMIT}/{@code OFFSET} <em>after</em> the locking clause, which is not
     * valid SQL.
     */
    @Query(value = """
            SELECT * FROM outbox_events o
            WHERE o.status = 'PENDING'
              AND o.next_attempt_at <= :now
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_events e
                  WHERE e.aggregate_id = o.aggregate_id
                    AND e.status = 'PENDING'
                    AND e.sequence_no < o.sequence_no)
            ORDER BY o.sequence_no
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimNextBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    long countByStatus(OutboxStatus status);

    /** Backs the {@code outbox.oldest.pending.age.seconds} gauge -- see OutboxMetrics. */
    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.status = :status")
    Optional<Instant> findOldestCreatedAt(@Param("status") OutboxStatus status);

    /**
     * Deletes up to {@code batchSize} published rows older than {@code cutoff}, and
     * returns how many it actually deleted so the caller can tell a partial batch (work
     * left to do) from a short one (done) -- see OutboxCleanupJob.
     *
     * The inner {@code SELECT ... FOR UPDATE SKIP LOCKED} is what makes this safe to run
     * from several instances at once: a plain {@code DELETE ... WHERE published_at <
     * cutoff} would have each instance block on the other's row locks, and would take
     * one unbounded lock over the whole expired range instead of {@code batchSize}
     * short-lived ones.
     */
    @Modifying
    @Query(value = """
            DELETE FROM outbox_events
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE status = 'PUBLISHED' AND published_at < :cutoff
                ORDER BY published_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
