package io.github.wynyga.ledger.adapter.out.persistence;

import org.hibernate.exception.ConstraintViolationException;

/** Recognises a violation of one named database constraint anywhere in an exception's causes. */
final class Constraints {
    private Constraints() {
    }

    static boolean violates(Throwable e, String constraint) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof ConstraintViolationException cv && constraint.equals(cv.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
