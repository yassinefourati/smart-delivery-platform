import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';

import { Dialog } from '../../../components/Dialog';
import { Field } from '../../../components/Field';
import { Loading } from '../../../components/Loading';
import { PageHeading } from '../../../components/PageHeading';
import { ProblemView } from '../../../components/ProblemView';
import ui from '../../../components/ui.module.css';
import {
  createProduct,
  deleteProduct,
  getProduct,
  listCategories,
  updateProduct,
} from '../../../lib/api/endpoints';
import { isApiProblem, isConflict } from '../../../lib/api/problem';
import { queryKeys } from '../../../lib/api/queryKeys';
import type { ProductRequest, ProductResponse } from '../../../lib/api/schemas/product';

interface FormState {
  sku: string;
  name: string;
  description: string;
  price: string;
  imageUrl: string;
  active: boolean;
  categoryId: string;
}

function initialState(p: ProductResponse | null): FormState {
  return {
    sku: p?.sku ?? '',
    name: p?.name ?? '',
    description: p?.description ?? '',
    price: p ? String(p.price) : '',
    imageUrl: p?.imageUrl ?? '',
    active: p?.active ?? true,
    categoryId: p?.categoryId ?? '',
  };
}

/**
 * PUT IS A FULL REPLACE. Every field is sent every time, including description and imageUrl --
 * omitting one would null a stored value that the person never touched. `active` is always
 * explicit because the server defaults it to false when absent.
 */
export function toProductRequest(form: FormState): ProductRequest {
  return {
    sku: form.sku.trim(),
    name: form.name.trim(),
    price: Number(form.price),
    active: form.active,
    categoryId: form.categoryId,
    ...(form.description.trim() ? { description: form.description.trim() } : {}),
    ...(form.imageUrl.trim() ? { imageUrl: form.imageUrl.trim() } : {}),
  };
}

function ProductForm({ product }: { product: ProductResponse | null }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [form, setForm] = useState(() => initialState(product));
  const [confirmDelete, setConfirmDelete] = useState(false);
  const categories = useQuery({
    queryKey: queryKeys.catalog.categories(),
    queryFn: ({ signal }) => listCategories({ signal }),
  });

  const invalidate = async (id?: string) => {
    await queryClient.invalidateQueries({ queryKey: queryKeys.catalog.products() });
    if (id) await queryClient.invalidateQueries({ queryKey: queryKeys.catalog.product(id) });
  };

  const save = useMutation({
    mutationFn: () =>
      product
        ? updateProduct(product.id, toProductRequest(form))
        : createProduct(toProductRequest(form)),
    // CONCURRENT_MODIFICATION means somebody else's write landed: our cache is stale either way.
    onSettled: () => invalidate(product?.id),
    onSuccess: () => void navigate('/admin/products'),
  });
  const remove = useMutation({
    mutationFn: () => deleteProduct(product?.id ?? ''),
    onSuccess: async () => {
      await invalidate(product?.id);
      void navigate('/admin/products');
    },
  });

  const conflict = isApiProblem(save.error) && isConflict(save.error);
  const text = (key: Exclude<keyof FormState, 'active'>) => ({
    value: form[key],
    onChange: (e: { target: { value: string } }) => setForm({ ...form, [key]: e.target.value }),
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate();
  };

  return (
    <form className={ui.form} onSubmit={submit} aria-label="Product">
      <Field label="SKU" error={conflict ? 'Another product already uses this SKU.' : null}>
        {(p) => <input {...p} required {...text('sku')} />}
      </Field>
      <Field label="Name">{(p) => <input {...p} required {...text('name')} />}</Field>
      <Field label="Description (optional)">
        {(p) => <textarea {...p} rows={3} {...text('description')} />}
      </Field>
      <div className={ui.grid2}>
        <Field label="Price (USD)">
          {(p) => <input {...p} required type="number" min="0.01" step="0.01" {...text('price')} />}
        </Field>
        <Field label="Category">
          {(p) => (
            <select {...p} required {...text('categoryId')}>
              <option value="">Choose a category</option>
              {categories.data?.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>
      <Field
        label="Image URL (optional)"
        hint="Stored, but the storefront does not display remote images: it draws a tile from the SKU."
      >
        {(p) => <input {...p} type="url" {...text('imageUrl')} />}
      </Field>
      <label className={ui.row}>
        <input
          type="checkbox"
          checked={form.active}
          onChange={(e) => setForm({ ...form, active: e.target.checked })}
        />
        Sold in the shop
      </label>
      <p className={`${ui.small} ${ui.muted}`}>
        Unticking this is the safe way to stop selling a product: orders for an inactive product are
        refused, and its order history stays intact.
      </p>
      {save.isError && !conflict ? <ProblemView error={save.error} /> : null}
      <div className={ui.row}>
        <button type="submit" className={`${ui.button} ${ui.primary}`} disabled={save.isPending}>
          {save.isPending ? 'Saving...' : product ? 'Save changes' : 'Create product'}
        </button>
        <Link to="/admin/products">Cancel</Link>
        {product ? (
          <button
            type="button"
            className={`${ui.button} ${ui.danger}`}
            onClick={() => setConfirmDelete(true)}
          >
            Delete
          </button>
        ) : null}
      </div>
      <Dialog
        open={confirmDelete}
        title="Delete this product?"
        onClose={() => setConfirmDelete(false)}
      >
        <p>This cannot be undone. Consider marking it as not sold instead.</p>
        {remove.isError ? <ProblemView error={remove.error} /> : null}
        <div className={ui.row}>
          <button
            type="button"
            className={`${ui.button} ${ui.danger}`}
            disabled={remove.isPending}
            onClick={() => remove.mutate()}
          >
            Delete product
          </button>
          <button type="button" className={ui.button} onClick={() => setConfirmDelete(false)}>
            Keep it
          </button>
        </div>
      </Dialog>
    </form>
  );
}

export function ProductFormPage() {
  const { productId } = useParams();
  const product = useQuery({
    queryKey: queryKeys.catalog.product(productId ?? ''),
    queryFn: ({ signal }) => getProduct(productId ?? '', { signal }),
    enabled: productId !== undefined,
    // A full-replace form must start from the CURRENT row, never a cached one.
    staleTime: 0,
  });
  const title = productId ? 'Edit product' : 'New product';
  return (
    <section className={ui.page}>
      <PageHeading title={title}>{title}</PageHeading>
      {productId === undefined ? (
        <ProductForm product={null} />
      ) : product.isPending ? (
        <Loading lines={6} />
      ) : product.isError ? (
        <ProblemView error={product.error} onRetry={() => void product.refetch()} />
      ) : (
        <ProductForm key={product.data.id} product={product.data} />
      )}
    </section>
  );
}
