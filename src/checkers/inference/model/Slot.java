package checkers.inference.model;

/**
 * Slots represent logical variables over which Constraints are generated.
 *
 * Each slot is attached to a code location that can hold an annotation OR has an intrinsic meaning
 * within type-systems.  E.g: an int literal is always NonNull but can't hold an annotation, nonetheless, we
 * generate a ConstantSlot representing the literal.
 *
 */
public abstract class Slot {

    /**
     * Used to locate this Slot in source code.  ASTRecords are written to Jaif files along
     * with the Annotation determined for this slot by the Solver.
     */
    private AnnotationLocation location;

    public Slot() {
        location = AnnotationLocation.MISSING_LOCATION;
    }

    public abstract <S, T> S serialize(Serializer<S, T> serializer);

    public Slot(AnnotationLocation location) {
        this.location = location;
    }

    public AnnotationLocation getLocation() {
        return location;
    }

    public void setLocation(AnnotationLocation location) {
        this.location = location;
    }

    public abstract Kind getKind();

    public enum Kind {
        VARIABLE, CONSTANT, REFINEMENT_VARIABLE, EXISTENTIAL_VARIABLE, COMB_VARIABLE
    }

    public boolean isVariable() {
        return getKind() != Kind.CONSTANT;
    }

    public boolean isConstant() {
        return getKind() == Kind.CONSTANT;
    }

}
