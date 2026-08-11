package com.smartdelivery.product.service;

import com.smartdelivery.product.domain.Category;
import com.smartdelivery.product.dto.CategoryRequest;
import com.smartdelivery.product.exception.CategoryInUseException;
import com.smartdelivery.product.exception.CategoryNotFoundException;
import com.smartdelivery.product.exception.DuplicateCategoryNameException;
import com.smartdelivery.product.repository.CategoryRepository;
import com.smartdelivery.product.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public Category create(CategoryRequest request) {
        if (categoryRepository.existsByNameIgnoreCase(request.name())) {
            throw new DuplicateCategoryNameException(request.name());
        }
        return categoryRepository.save(new Category(request.name(), request.description()));
    }

    @Transactional(readOnly = true)
    public Category getById(UUID id) {
        return categoryRepository.findById(id).orElseThrow(() -> new CategoryNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Category> list() {
        return categoryRepository.findAll();
    }

    @Transactional
    public Category update(UUID id, CategoryRequest request) {
        Category category = getById(id);
        if (!category.getName().equalsIgnoreCase(request.name()) && categoryRepository.existsByNameIgnoreCase(request.name())) {
            throw new DuplicateCategoryNameException(request.name());
        }
        category.setName(request.name());
        category.setDescription(request.description());
        return category;
    }

    @Transactional
    public void delete(UUID id) {
        Category category = getById(id);
        if (productRepository.countByCategoryId(id) > 0) {
            throw new CategoryInUseException(id);
        }
        categoryRepository.delete(category);
    }
}
