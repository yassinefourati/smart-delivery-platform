package com.smartdelivery.product.service;

import com.smartdelivery.product.domain.Category;
import com.smartdelivery.product.dto.CategoryRequest;
import com.smartdelivery.product.exception.CategoryInUseException;
import com.smartdelivery.product.exception.CategoryNotFoundException;
import com.smartdelivery.product.exception.DuplicateCategoryNameException;
import com.smartdelivery.product.repository.CategoryRepository;
import com.smartdelivery.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ProductRepository productRepository;

    private CategoryService service() {
        return new CategoryService(categoryRepository, productRepository);
    }

    private Category categoryWithId(String name) {
        Category category = new Category(name, "desc");
        ReflectionTestUtils.setField(category, "id", UUID.randomUUID());
        return category;
    }

    @Test
    void createRejectsDuplicateNameCaseInsensitively() {
        CategoryService service = service();
        when(categoryRepository.existsByNameIgnoreCase("electronics")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CategoryRequest("electronics", "desc")))
                .isInstanceOf(DuplicateCategoryNameException.class);

        verify(categoryRepository, never()).save(any());
    }

    @Test
    void getByIdThrowsWhenMissing() {
        CategoryService service = service();
        UUID id = UUID.randomUUID();
        when(categoryRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void deleteRejectsCategoryStillReferencedByProducts() {
        CategoryService service = service();
        Category category = categoryWithId("Electronics");
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
        when(productRepository.countByCategoryId(category.getId())).thenReturn(3L);

        assertThatThrownBy(() -> service.delete(category.getId())).isInstanceOf(CategoryInUseException.class);

        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void deleteRemovesUnusedCategory() {
        CategoryService service = service();
        Category category = categoryWithId("Electronics");
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
        when(productRepository.countByCategoryId(category.getId())).thenReturn(0L);

        service.delete(category.getId());

        verify(categoryRepository).delete(category);
    }

    @Test
    void updateAllowsKeepingTheSameNameWithoutTriggeringDuplicateCheck() {
        CategoryService service = service();
        Category category = categoryWithId("Electronics");
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));

        Category updated = service.update(category.getId(), new CategoryRequest("Electronics", "new desc"));

        assertThat(updated.getDescription()).isEqualTo("new desc");
        verify(categoryRepository, never()).existsByNameIgnoreCase(any());
    }
}
