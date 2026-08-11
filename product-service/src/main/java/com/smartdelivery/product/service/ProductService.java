package com.smartdelivery.product.service;

import com.smartdelivery.product.config.CacheConfig;
import com.smartdelivery.product.domain.Category;
import com.smartdelivery.product.domain.Product;
import com.smartdelivery.product.dto.ProductMapper;
import com.smartdelivery.product.dto.ProductRequest;
import com.smartdelivery.product.dto.ProductResponse;
import com.smartdelivery.product.exception.DuplicateSkuException;
import com.smartdelivery.product.exception.ProductNotFoundException;
import com.smartdelivery.product.repository.ProductRepository;
import com.smartdelivery.product.repository.ProductSpecifications;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryService categoryService;

    public ProductService(ProductRepository productRepository, CategoryService categoryService) {
        this.productRepository = productRepository;
        this.categoryService = categoryService;
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        if (productRepository.existsBySkuIgnoreCase(request.sku())) {
            throw new DuplicateSkuException(request.sku());
        }
        Category category = categoryService.getById(request.categoryId());
        Product product = new Product(
                request.sku(), request.name(), request.description(), request.price(),
                request.imageUrl(), request.active(), category);
        return ProductMapper.toResponse(productRepository.save(product));
    }

    /**
     * Individual product reads are the hot path (see ADR 005) and are cached; every
     * mutation below evicts this same key so a reader never sees stale data for longer
     * than one write's worth of latency.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "#id")
    public ProductResponse getById(UUID id) {
        return productRepository.findById(id)
                .map(ProductMapper::toResponse)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> search(UUID categoryId, BigDecimal minPrice, BigDecimal maxPrice, String search, Pageable pageable) {
        List<Specification<Product>> criteria = Arrays.asList(
                ProductSpecifications.active(),
                ProductSpecifications.categoryId(categoryId),
                ProductSpecifications.priceAtLeast(minPrice),
                ProductSpecifications.priceAtMost(maxPrice),
                ProductSpecifications.textSearch(search));

        Specification<Product> specification = Specification.allOf(criteria.stream().filter(Objects::nonNull).toList());
        return productRepository.findAll(specification, pageable).map(ProductMapper::toResponse);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "#id")
    public ProductResponse update(UUID id, ProductRequest request) {
        Product product = productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));

        if (!product.getSku().equalsIgnoreCase(request.sku()) && productRepository.existsBySkuIgnoreCase(request.sku())) {
            throw new DuplicateSkuException(request.sku());
        }

        Category category = categoryService.getById(request.categoryId());
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setImageUrl(request.imageUrl());
        product.setActive(request.active());
        product.setCategory(category);

        return ProductMapper.toResponse(product);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "#id")
    public void delete(UUID id) {
        if (!productRepository.existsById(id)) {
            throw new ProductNotFoundException(id);
        }
        productRepository.deleteById(id);
    }
}
