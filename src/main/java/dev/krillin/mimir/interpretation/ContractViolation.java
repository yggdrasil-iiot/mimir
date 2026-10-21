package dev.krillin.mimir.interpretation;

/** A document breaks the interpretation contract. {@link #code()} is stable and is what tests assert. */
public final class ContractViolation extends RuntimeException {

    private final String code;

    public ContractViolation(String code, String detail) {
        super(code + (detail == null || detail.isEmpty() ? "" : " at " + detail));
        this.code = code;
    }

    public String code() {
        return code;
    }
}
