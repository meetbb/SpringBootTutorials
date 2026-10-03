package com.sunbreathingcode.PaginationDemo.exception;

import java.util.Set;

public class InvalidSortFieldException extends RuntimeException {

    public InvalidSortFieldException(String field, Set<String> allowedFields) {
        super("sort field '" + field + "' is not supported; allowed fields: " + allowedFields);
    }
}
