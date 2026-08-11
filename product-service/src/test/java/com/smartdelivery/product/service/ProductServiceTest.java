package com.smartdelivery.product.service;

import com.smartdelivery.product.domain.Category;
import com.smartdelivery.product.domain.Product;
import com.smartdelivery.product.dto.ProductRequest;
import com.smartdelivery.product.dto.ProductResponse;
import com.smartdelivery.product.exception.DuplicateSkuException;
import com.smartdelivery.product.exception.ProductNotFoundException;
import com.smartdelivery.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryService categoryService;

    private ProductService service() {
        return new ProductService(productRepository, categoryService);
    }

    private Category categoryWithId() {
        Category category = new Category("Electronics", "desc");
        ReflectionTestUtils.setField(category, "id", UUID.randomUUID());
        return category;
    }

    private Product productWithId(Category category, String sku, BigDecimal price) {
        Product product = new Product(sku, "Widget", "desc", price, null, true, category);
        ReflectionTestUtils.setField(product, "id", UUID.randomUUID());
        return product;
    }

    @Test
    void createRejectsDuplicateSkuWithoutTouchingCategoryLookup() {
        ProductService service = service();
        var request = new ProductRequest("SKU-1", "Widget", "desc", BigDecimal.TEN, null, true, UUID.randomUUID());
        when(productRepository.existsBySkuIgnoreCase("SKU-1")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(DuplicateSkuException.class);

        verify(categoryService, never()).getById(any());
        verify(productRepository, never()).save(any());
    }

    @Test
    void createResolvesCategoryAndPersistsProduct() {
        ProductService service = service();
        Category category = categoryWithId();
        var request = new ProductRequest("SKU-2", "Widget", "desc", BigDecimal.TEN, "http://img", true, category.getId());
        when(productRepository.existsBySkuIgnoreCase("SKU-2")).thenReturn(false);
        when(categoryService.getById(category.getId())).thenReturn(category);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product p = invocation.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });

        ProductResponse response = service.create(request);

        assertThat(response.sku()).isEqualTo("SKU-2");
        assertThat(response.categoryId()).isEqualTo(category.getId());
    }

    @Test
    void getByIdThrowsWhenMissing() {
        ProductService service = service();
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void updateRejectsSkuCollisionWithAnotherProduct() {
        ProductService service = service();
        Category category = categoryWithId();
        Product existing = productWithId(category, "SKU-OLD", BigDecimal.TEN);
        when(productRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(productRepository.existsBySkuIgnoreCase("SKU-TAKEN")).thenReturn(true);

        var request = new ProductRequest("SKU-TAKEN", "New name", "desc", BigDecimal.ONE, null, true, category.getId());

        assertThatThrownBy(() -> service.update(existing.getId(), request)).isInstanceOf(DuplicateSkuException.class);
    }

    @Test
    void updateAppliesAllMutableFields() {
        ProductService service = service();
        Category oldCategory = categoryWithId();
        Category newCategory = categoryWithId();
        Product existing = productWithId(oldCategory, "SKU-1", BigDecimal.TEN);
        when(productRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(categoryService.getById(newCategory.getId())).thenReturn(newCategory);

        var request = new ProductRequest("SKU-1", "Renamed", "new desc", new BigDecimal("19.99"), "http://x", false, newCategory.getId());
        ProductResponse response = service.update(existing.getId(), request);

        assertThat(response.name()).isEqualTo("Renamed");
        assertThat(response.price()).isEqualByComparingTo("19.99");
        assertThat(response.active()).isFalse();
        assertThat(response.categoryId()).isEqualTo(newCategory.getId());
    }

    @Test
    void deleteThrowsWhenProductMissing() {
        ProductService service = service();
        UUID id = UUID.randomUUID();
        when(productRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ProductNotFoundException.class);
        verify(productRepository, never()).deleteById(any());
    }

    @Test
    void deleteRemovesExistingProduct() {
        ProductService service = service();
        UUID id = UUID.randomUUID();
        when(productRepository.existsById(id)).thenReturn(true);

        service.delete(id);

        verify(productRepository).deleteById(id);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchDelegatesToRepositoryWithASpecificationAndMapsResults() {
        ProductService service = service();
        Category category = categoryWithId();
        Product product = productWithId(category, "SKU-1", BigDecimal.TEN);
        Page<Product> page = new PageImpl<>(List.of(product));
        when(productRepository.findAll(any(Specification.class), eq(PageRequest.of(0, 20)))).thenReturn(page);

        Page<ProductResponse> result = service.search(null, null, null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).sku()).isEqualTo("SKU-1");
    }
}
