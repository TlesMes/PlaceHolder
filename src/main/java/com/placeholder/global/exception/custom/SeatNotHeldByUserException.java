package com.placeholder.global.exception.custom;

public class SeatNotHeldByUserException extends RuntimeException {
    public SeatNotHeldByUserException(String message) {
        super(message);
    }
}
