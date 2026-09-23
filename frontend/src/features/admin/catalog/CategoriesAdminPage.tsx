import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';

import { Field } from '../../../components/Field';
import { Loading } from '../../../components/Loading';
import { PageHeading } from '../../../components/PageHeading';
import { ProblemView } from '../../../components/ProblemView';
import ui from '../../../components/ui.module.css';
import {
  createCategory,
  deleteCategory,
  listCategories,
  updateCategory,
} from '../../../lib/api/endpoints';
import { isApiProblem, isConflict } from '../../../lib/api/problem';
import { queryKeys } from '../../../lib/api/queryKeys';
import type { CategoryResponse } from '../../../lib/api/schemas/product';

function CategoryForm({
  existing,
  onDone,
}: {
  existing: CategoryResponse | null;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(existing?.name ?? '');
  const [description, setDescription] = useState(existing?.description ?? '');
  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: name.trim(),
        ...(description.trim() ? { description: description.trim() } : {}),
      };
      return existing ? updateCategory(existing.id, body) : createCategory(body);
    },
    // Products carry a denormalised categoryName, so a rename makes product pages stale too.
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.catalog.categories() });
      await queryClient.invalidateQueries({ queryKey: queryKeys.catalog.products() });
    },
    onSuccess: onDone,
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate();
  };
  return (
    <form
      className={`${ui.form} ${ui.card}`}
      onSubmit={submit}
      aria-label={existing ? 'Edit category' : 'New category'}
    >
      <Field label="Name">
        {(p) => <input {...p} required value={name} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <Field label="Description (optional)">
        {(p) => (
          <input {...p} value={description} onChange={(e) => setDescription(e.target.value)} />
        )}
      </Field>
      {save.isError ? (
        isApiProblem(save.error) && isConflict(save.error) ? (
          <p role="alert" className={`${ui.notice} ${ui.error}`}>
            A category with this name already exists.
          </p>
        ) : (
          <ProblemView error={save.error} />
        )
      ) : null}
      <div className={ui.row}>
        <button type="submit" className={`${ui.button} ${ui.primary}`} disabled={save.isPending}>
          Save
        </button>
        <button type="button" className={ui.button} onClick={onDone}>
          Cancel
        </button>
      </div>
    </form>
  );
}

export function CategoriesAdminPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<CategoryResponse | 'new' | null>(null);
  const categories = useQuery({
    queryKey: queryKeys.catalog.categories(),
    queryFn: ({ signal }) => listCategories({ signal }),
  });
  const remove = useMutation({
    mutationFn: (id: string) => deleteCategory(id),
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.catalog.categories() }),
  });
  const removeConflict = isApiProblem(remove.error) && isConflict(remove.error);
  return (
    <section className={ui.page}>
      <PageHeading title="Categories">Categories</PageHeading>
      {editing ? (
        <CategoryForm
          key={editing === 'new' ? 'new' : editing.id}
          existing={editing === 'new' ? null : editing}
          onDone={() => setEditing(null)}
        />
      ) : (
        <div>
          <button
            type="button"
            className={`${ui.button} ${ui.primary}`}
            onClick={() => setEditing('new')}
          >
            New category
          </button>
        </div>
      )}
      {removeConflict ? (
        <p role="alert" className={`${ui.notice} ${ui.error}`}>
          This category could not be deleted, most likely because products still belong to it. Move
          them to another category first.
        </p>
      ) : remove.isError ? (
        <ProblemView error={remove.error} />
      ) : null}
      {categories.isPending ? (
        <Loading />
      ) : categories.isError ? (
        <ProblemView error={categories.error} onRetry={() => void categories.refetch()} />
      ) : (
        <table className={ui.table}>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Description</th>
              <th scope="col">
                <span className={ui.visuallyHidden}>Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {categories.data.map((c) => (
              <tr key={c.id}>
                <td>{c.name}</td>
                <td>{c.description ?? ''}</td>
                <td className={ui.row}>
                  <button type="button" className={ui.linkButton} onClick={() => setEditing(c)}>
                    Edit <span className={ui.visuallyHidden}>{c.name}</span>
                  </button>
                  <button
                    type="button"
                    className={ui.linkButton}
                    disabled={remove.isPending}
                    onClick={() => remove.mutate(c.id)}
                  >
                    Delete <span className={ui.visuallyHidden}>{c.name}</span>
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
