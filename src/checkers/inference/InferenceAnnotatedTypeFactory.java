package checkers.inference;

import java.lang.annotation.Annotation;
import java.util.*;

import checkers.basetype.BaseTypeChecker;
import checkers.inference.dataflow.InferenceAnalysis;
import checkers.inference.model.*;
import checkers.inference.quals.CombVarAnnot;
import checkers.inference.quals.LiteralAnnot;
import checkers.inference.quals.RefineVarAnnot;
import checkers.inference.quals.VarAnnot;
import checkers.inference.util.CopyUtil;
import checkers.inference.util.InferenceUtil;
import checkers.quals.Unqualified;
import checkers.types.AnnotatedTypeMirror;
import checkers.types.AnnotatedTypeMirror.*;
import checkers.types.QualifierHierarchy;
import checkers.types.TreeAnnotator;
import checkers.types.TypeHierarchy;
import checkers.util.AnnotatedTypes;
import checkers.util.MultiGraphQualifierHierarchy;
import com.sun.source.tree.*;
import javacutils.Pair;

import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;

import checkers.basetype.BaseAnnotatedTypeFactory;
import checkers.flow.CFAbstractAnalysis;
import checkers.flow.CFAnalysis;
import checkers.flow.CFStore;
import checkers.flow.CFTransfer;
import checkers.flow.CFValue;
import javacutils.TreeUtils;

/**
 * InferenceAnnotatedTypeFactory is responsible for creating AnnotatedTypeMirrors that are annotated with
 * "variable" or "real" annotations.  Variable annotations, represented by checkers.inference.@VarAnnot, indicate
 * an annotation with a value to be inferred.  Real annotations, those found in the hierarchy of the type system
 * for which we are inferring values, indicate that the given type has a constant value in the "real" type system.
 *
 * Adding annotations is accomplished through three means:
 * 1.  If we have the source code for a particular type, the InferenceTreeAnnotator and the VariableAnnotator
 * will add the relevant annotation (either VarAnnot or constant real annotation) based on the type's corresponding
 * tree and the rules of the InferrableChecker.  If the InferrableChecker determines that a value is constant
 * then the realAnnotatedTypeFactory is consulted to get this value.
 * @see checkers.inference.InferenceAnnotatedTypeFactory#annotateImplicit(com.sun.source.tree.Tree, checkers.types.AnnotatedTypeMirror)
 *
 * 2.  If we do NOT have the source code then the realAnnotatedTypeFactory is used to determine a constant value
 * to place on the given "library", i.e. from bytecode, type.
 * @see checkers.inference.InferenceAnnotatedTypeFactory#annotateImplicit(javax.lang.model.element.Element, checkers.types.AnnotatedTypeMirror)
 *
 * 3.  Types representing declarations generated using methods 1 and 2 are stored via
 * VariableAnnotator#storeElementType.  If these elements are encountered again, the annotations from the stored
 * type are copied to the annotations of the type being annotated.
 * @see checkers.inference.InferenceAnnotatedTypeFactory#annotateImplicit(javax.lang.model.element.Element, checkers.types.AnnotatedTypeMirror)
 *
 * Note: a number of constraints are created by members of this class
 * @see checkers.inference.InferenceAnnotatedTypeFactory.InferenceQualifierHierarchy
 * @see checkers.inference.InferenceAnnotatedTypeFactory.InferenceTypeHierarchy
 *
 * Finally, Variables are created while flow is being performed (every time getAnnotatedType is called on a
 * class tree that hasn't yet been annotated).  Constraints are created after flow has been for a given class when
 * the visitor requests types.
 */
public class InferenceAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

    private final boolean withCombineConstraints;
    private final VariableAnnotator variableAnnotator;
    private final InferenceTreeAnnotator inferenceTreeAnnotator;
    private final BaseAnnotatedTypeFactory realTypeFactory;

    private InferrableChecker realChecker;
    private InferenceChecker inferenceChecker;
    private SlotManager slotManager;
    private ConstraintManager constraintManager;

    public InferenceAnnotatedTypeFactory(
            InferenceChecker inferenceChecker,
            boolean withCombineConstraints,
            BaseAnnotatedTypeFactory realTypeFactory,
            InferrableChecker realChecker,
            SlotManager slotManager,
            ConstraintManager constraintManager) {

        super(inferenceChecker, true);

        this.withCombineConstraints = withCombineConstraints;
        variableAnnotator = new VariableAnnotator(this, realTypeFactory, realChecker, slotManager);
        inferenceTreeAnnotator = new InferenceTreeAnnotator(this, realChecker, realTypeFactory, variableAnnotator,
                                                            slotManager);
        this.realTypeFactory = realTypeFactory;
        this.inferenceChecker = inferenceChecker;
        this.realChecker = realChecker;
        this.slotManager = slotManager;
        this.constraintManager = constraintManager;
        postInit();
    }

    @Override
    protected CFAnalysis createFlowAnalysis(List<Pair<VariableElement, CFValue>> fieldValues) {
        return realChecker.createInferenceAnalysis(inferenceChecker, this, fieldValues, slotManager, constraintManager, realChecker);
    }

    @Override
    public CFTransfer createFlowTransferFunction(CFAbstractAnalysis<CFValue, CFStore, CFTransfer> analysis) {
        return realChecker.createInferenceTransferFunction((InferenceAnalysis) analysis);
    }

    @Override
    public TreeAnnotator createTreeAnnotator() {
        return inferenceTreeAnnotator;
    }

    @Override
    protected MultiGraphQualifierHierarchy.MultiGraphFactory createQualifierHierarchyFactory() {
        return new MultiGraphQualifierHierarchy.MultiGraphFactory(this);
    }

    @Override
    public QualifierHierarchy createQualifierHierarchy( MultiGraphQualifierHierarchy.MultiGraphFactory factory ) {
        return new InferenceQualifierHierarchy(factory);
    }

    @Override
    protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
        final Set<Class<? extends Annotation>> typeQualifiers = new HashSet<>();

        typeQualifiers.add(Unqualified.class);
        typeQualifiers.add(VarAnnot.class);
        typeQualifiers.add(RefineVarAnnot.class);
        typeQualifiers.add(CombVarAnnot.class);
        typeQualifiers.add(LiteralAnnot.class);

        typeQualifiers.addAll(realTypeFactory.getSupportedTypeQualifiers());
        return Collections.unmodifiableSet(typeQualifiers);
    }

    /**
     *  Copies the primary annotations on the use type "type" onto each "supertype".
     *  E.g. for a call:
     *      postDirectSuperTypes( @9 ArrayList< @7 String>, List( @4 List<@7 String>, @0 Object ) )
     *  we would like supertypes to become:
     *      List( @9 List<@7 String>, @9 Object )
     *
     * This does NOTHING to the type parameters of a declared type.  The superTypeFinder should appropriately
     * fix these up.
     */
    @Override
    protected void postDirectSuperTypes(AnnotatedTypeMirror type, List<? extends AnnotatedTypeMirror> supertypes) {

        //TODO: Move postdirectSupertypes to a "copyTypeToSuperType method" that can just be called by this method?
        //At the time of writing this is the same as AnnotatedTypeFactory.postDirectSuperTypes
        //we cannot call super.postDirectSuperTypes because GenericAnnotatedTypeFactory will cause
        //annotateImplicit(element,type) to be called on the supertype which will overwrite the annotations from type
        //with those for the declaration of the super type
        Set<AnnotationMirror> annotations = type.getEffectiveAnnotations();
        for (AnnotatedTypeMirror supertype : supertypes) {
            if (!annotations.equals(supertype.getEffectiveAnnotations())) {
                supertype.clearAnnotations();
                supertype.addAnnotations(annotations);
            }
        }
    }

    @Override
    public void postAsMemberOf(final AnnotatedTypeMirror type,
                               final AnnotatedTypeMirror owner, final Element element) {
        final TypeKind typeKind = type.getKind();
        if(typeKind != TypeKind.DECLARED && typeKind != TypeKind.ARRAY) {
            return;
        }

        final ElementKind elementKind = element.getKind();
        if(elementKind == ElementKind.LOCAL_VARIABLE || elementKind == ElementKind.PARAMETER) {
            return;
        }

        //TODO: Look at old implementation and add combine constraints
    }

    /**
     * TODO: The implementation in AnnotatedTypeFactory essentially replaces the parameterized bounds with concrete bounds
     * TODO: (e.g. <T extends @Nullable Object, E extends T> => <T extends @Nullable Object, E extends @Nullable Object>
     * TODO: TO CORRECTLY MODEL THE RESULTING CONSTRAINTS WOULD WE NOT DO THE SAME THING?  OR CREATE A COMBVAR
     * TODO: FOR THAT LOCATION?
     */
    @Override
    public List<AnnotatedTypeVariable> typeVariablesFromUse(final AnnotatedDeclaredType useType, final TypeElement element ) {
        //The type of the class in which the type params were declared
        final AnnotatedDeclaredType ownerOfTypeParams = getAnnotatedType(element);
        final List<AnnotatedTypeMirror> declaredTypeParameters = ownerOfTypeParams.getTypeArguments();

        final List<AnnotatedTypeVariable> result = new ArrayList<AnnotatedTypeVariable>( declaredTypeParameters.size() );

        for( int i = 0; i < declaredTypeParameters.size(); i++ ) {
            final AnnotatedTypeVariable declaredTypeParam = (AnnotatedTypeVariable) declaredTypeParameters.get(i);
            result.add( declaredTypeParam );

            //TODO: Original InferenceAnnotatedTypeFactory#typeVariablesFromUse would create a combine constraint
            //TODO: between the useType and the effectiveUpperBound of the declaredTypeParameter
            //TODO: and then copy the annotations from the type with the CombVars to the declared type
        }

        return result;
    }

    /**
     * @see checkers.types.AnnotatedTypeFactory#methodFromUse(com.sun.source.tree.MethodInvocationTree)
     * TODO: This is essentially the default implementation of AnnotatedTypeFactory.methodFromUse with a space to later
     * TODO: add comb constraints.  One difference is how the receiver is gotten.  Perhaps we should just
     * TODO: change getSelfType?  But I am not sure where getSelfType is used yet
     * @param methodInvocationTree
     * @return
     */
    @Override
    public Pair<AnnotatedExecutableType, List<AnnotatedTypeMirror>> methodFromUse(final MethodInvocationTree methodInvocationTree) {
        assert methodInvocationTree != null : "MethodInvocationTree in methodFromUse was null.  " +
                                              "Current path:\n" + this.visitorState.getPath();
        final ExecutableElement methodElem = TreeUtils.elementFromUse(methodInvocationTree);

        //TODO: Used in comb constraints, going to leave it in to ensure the element has been visited
        final AnnotatedExecutableType methodType = getAnnotatedType(methodElem);

        final ExpressionTree methodSelectExpression = methodInvocationTree.getMethodSelect();
        final AnnotatedTypeMirror receiverType;
        if (methodSelectExpression.getKind() == Tree.Kind.MEMBER_SELECT) {
            receiverType = getAnnotatedType(((MemberSelectTree) methodSelectExpression).getExpression());
        } else {
            receiverType = getSelfType(methodInvocationTree);
        }

        assert receiverType != null : "Null receiver type when getting method from use for tree ( " + methodInvocationTree + " )";

        //TODO: Add CombConstraints for method parameter types as well as return types

        //TODO: Is the asMemberOf correct, was not in Werner's original implementation but I had added it
        //TODO: It is also what the AnnotatedTypeFactory default implementation does
        final AnnotatedExecutableType methodOfReceiver = AnnotatedTypes.asMemberOf(types, this, receiverType, methodElem);

        return substituteTypeArgs(methodInvocationTree, methodElem, methodOfReceiver);
    }

    /**
     * TODO: Similar but not the same as AnnotatedTypeFactory.constructorFromUse with space set aside from
     * TODO: comb constraints, track down the differences with constructorFromUse
     * Note: super() and this() calls
     * @see checkers.types.AnnotatedTypeFactory#constructorFromUse(com.sun.source.tree.NewClassTree)
     *
     * @param newClassTree
     * @return
     */
    @Override
    public Pair<AnnotatedExecutableType, List<AnnotatedTypeMirror>> constructorFromUse(final NewClassTree newClassTree) {
        assert newClassTree != null : "NewClassTree was null when attempting to get constructorFromUse. " +
                                      "Current path:\n" + this.visitorState.getPath();

        final ExecutableElement constructorElem = TreeUtils.elementFromUse(newClassTree);
        final AnnotatedExecutableType constructorType = getAnnotatedType(constructorElem);

        //TODO: ADD CombConstraints
        //TODO: Should we  be doing asMemberOf like super?
        return substituteTypeArgs(newClassTree, constructorElem, constructorType);
    }

    /**
     * Get a map of the type arguments for any type variables in tree.  Create a list of type arguments by
     * replacing each type parameter of exeEle by it's corresponding argument in the map.  Substitute the
     * type parameters in exeType with those in the type arg map.
     *
     * @param expressionTree Tree representing the method or constructor we are analyzing
     * @param methodElement The element corresponding with tree
     * @param methodType The type as determined by this class of exeEle
     * @return A list of the actual type arguments for the type parameters of exeEle and exeType with it's type
     *         parameters replaced by the actual type arguments
     */
    private <EXP_TREE extends ExpressionTree> Pair<AnnotatedExecutableType, List<AnnotatedTypeMirror>> substituteTypeArgs(
            EXP_TREE expressionTree, final ExecutableElement methodElement, final AnnotatedExecutableType methodType) {

        // determine substitution for method type variables
        final Map<AnnotatedTypeVariable, AnnotatedTypeMirror> typeVarMapping =
                AnnotatedTypes.findTypeArguments(processingEnv, this, expressionTree);

        if( !typeVarMapping.isEmpty() ) {
            return Pair.<AnnotatedExecutableType, List<AnnotatedTypeMirror>>of(methodType, new LinkedList<AnnotatedTypeMirror>());
        } //else

        // We take the type variables from the method element, not from the annotated method.
        // For some reason, this way works, the other one doesn't.  //TODO: IS THAT TRUE?
        final List<AnnotatedTypeVariable> foundTypeVars   = new LinkedList<>();
        final List<AnnotatedTypeVariable> missingTypeVars = new LinkedList<>();

        for ( final TypeParameterElement typeParamElem : methodElement.getTypeParameters() ) {
            final AnnotatedTypeVariable typeParam = (AnnotatedTypeVariable) getAnnotatedType(typeParamElem);
            if (typeVarMapping.containsKey(typeParam)) {
                foundTypeVars.add(typeParam);
            } else {
                missingTypeVars.add(typeParam);
            }
        }

        //This used to be a println
        assert missingTypeVars.isEmpty() :"InferenceAnnotatedTypeFactory.methodFromUse did not find a mapping for" +
                                           "the following type params:\n" + InferenceUtil.join(missingTypeVars, "\n") +
                                           "in the inferred type arguments: " + InferenceUtil.join(typeVarMapping);

        final List<AnnotatedTypeMirror> actualTypeArgs = new ArrayList<>(foundTypeVars.size());
        for (final AnnotatedTypeVariable found : foundTypeVars) {
            actualTypeArgs.add(typeVarMapping.get(found));
        }

        final AnnotatedExecutableType actualExeType = methodType.substitute(typeVarMapping);

        return Pair.of(actualExeType, actualTypeArgs);
    }


    protected void performFlowAnalysis(final ClassTree classTree) {
        final InferenceMain inferenceMain = InferenceMain.getInstance();
        inferenceMain.setPerformingFlow(true);
        super.performFlowAnalysis(classTree);
        inferenceMain.setPerformingFlow(false);
    }

    protected void annotateImplicitWithFlow(final Tree tree, final AnnotatedTypeMirror type) {

        if ( tree instanceof ClassTree ) {
            final ClassTree classTree = (ClassTree) tree;
            if (!scannedClasses.containsKey(classTree)) {
                performFlowAnalysis(classTree);
            }
        }

        treeAnnotator.visit(tree, type);

        final CFValue inferredValue = getInferredValueFor(tree);
        if (inferredValue != null) {
            applyInferredAnnotations(type, inferredValue);
        }

        //TODO: WE BELIEVE WE DO NOT NEED TO USE THE OLD STYLE replaceWithRefVar (see old InferenceAnnotatedTypeFactory.scala)
    }

    //TODO: I don't think this method is needed, but we should verify this.
//    @Override
//    public AnnotatedTypeMirror getAnnotatedTypeFromTypeTree(final Tree tree) {
//
//        if (inferenceChecker.extImplsTreeCache.contains(tree)) {
//            inferenceChecker.extImplsTreeCache(tree)
//
//        } else {
//            super.getAnnotatedTypeFromTypeTree(tree)
//
//        }
//    }

    /**
     * TODO: Expand
     * If we have a cached AnnotatedTypeMirror for the element then copy its annotations to type
     * else if we can get the source tree for the declaration of that element visit it with the tree annotator
     * else get the AnnotatedTypeMirror from the real AnnotatedTypeFactory and copy its annotations to type
     * @param element The element to annotate
     * @param type The AnnotatedTypeMirror corresponding to element
     * */
    public void annotateImplicit(final Element element, final AnnotatedTypeMirror type) {
        if (!variableAnnotator.annotateElementFromStore(element, type)) {
            final Tree declaration = declarationFromElement(element);
            if (declaration != null) {
                treeAnnotator.visit(declaration, type);
            } else {
                final AnnotatedTypeMirror realType = realTypeFactory.getAnnotatedType(element);
                CopyUtil.copyAnnotations(realType, type);
            }
        }
    }

    public void setRoot(final CompilationUnitTree root) {
        //TODO: THERE MAY BE STORES WE WANT TO CLEAR, PERHAPS ELEMENTS FOR LOCAL VARIABLES
        //TODO: IN THE PREVIOUS COMPILATION UNIT IN VARIABLE ANNOTATOR
        this.realTypeFactory.setRoot( root );
        super.setRoot( root );
    }

    /**
     *  The InferenceTypeHierarchy along with the InferenceQualifierHierarchy is responsible for
     *  creating a subtype and equality constraints. Normally the methods of these two classes are queried
     *  in order to verify that two types have a required subtype relationship or to determine what to do
     *  based on the relationship between the two types.  However, in the InferenceQualifierHierarchy
     *  calls to isSubtype generate subtype/equality constraints between the input parameters and returns true.
     *
     *  This class generally delegates calls to the InferenceQualifierHierarchy which in turn generates
     *  the correct constraints.
     */
    private class InferenceTypeHierarchy extends TypeHierarchy {

        //TODO: Think through and add any missing constraints
        private InferenceMain inferenceMain = InferenceMain.getInstance();

        /**
         * Constructs an instance of {@code TypeHierarchy} for the type system
         * whose qualifiers represented in qualifierHierarchy.
         *
         * @param checker The type-checker to use
         * @param qualifierHierarchy The qualifier hierarchy to use
         */
        public InferenceTypeHierarchy(final BaseTypeChecker checker, final QualifierHierarchy qualifierHierarchy) {
            super(checker, qualifierHierarchy);
        }

        // copied from super, also allow type arguments with different qualifiers and create equality constraints
        protected boolean isSubtypeAsTypeArgument(final AnnotatedTypeMirror rhs, final AnnotatedTypeMirror lhs) {

            if (lhs.getKind() == TypeKind.WILDCARD && rhs.getKind() != TypeKind.WILDCARD) {
                if (visited.contains(lhs))
                    return true;

                visited.add(lhs);

                final AnnotatedTypeMirror lhsAsWildcard = ((AnnotatedWildcardType)lhs).getExtendsBound();
                if (lhsAsWildcard == null)
                    return true;

                return isSubtypeImpl(rhs, lhsAsWildcard);
            }

            if (lhs.getKind() == TypeKind.WILDCARD && rhs.getKind() == TypeKind.WILDCARD) {
                return isSubtype(((AnnotatedWildcardType) rhs).getExtendsBound(),
                                 ((AnnotatedWildcardType) lhs).getExtendsBound());
            }

            if (lhs.getKind() == TypeKind.TYPEVAR && rhs.getKind() != TypeKind.TYPEVAR) {
                if (visited.contains(lhs)) {
                    return true;
                }

                visited.add(lhs);
                return isSubtype(rhs, ((AnnotatedTypeVariable) lhs).getUpperBound());
            }

            final Set<AnnotationMirror> lhsAnnos = lhs.getAnnotations();
            final Set<AnnotationMirror> rhsAnnos = rhs.getAnnotations();

            //TODO: Do something more intelligent with raw types?
            assert lhsAnnos.size() == rhsAnnos.size() : "Encountered raw types: rhs ( " + rhs + " ) lhs ( " + lhs + " ) " +
                "currentPath: ( " +  InferenceAnnotatedTypeFactory.this.visitorState.getPath() + " )";

            //TODO: The original behavior was to check it and return true if size != 1
            assert lhsAnnos.size() == 1 : "Only 1 annotation expected.  Types: rhs ( " + rhs + " ) lhs ( " + lhs + " ) " +
                "currentPath: ( " +  InferenceAnnotatedTypeFactory.this.visitorState.getPath() + " )";

            final AnnotationMirror leftAnno  = lhsAnnos.iterator().next();
            final AnnotationMirror rightAnno = rhsAnnos.iterator().next();

            if (!inferenceMain.isPerformingFlow()) {
                final Slot leftSlot  = inferenceMain.getSlotManager().getSlot( leftAnno  );
                final Slot rightSlot = inferenceMain.getSlotManager().getSlot( rightAnno );
                inferenceMain.getConstraintManager().add(new EqualityConstraint(leftSlot, rightSlot));
            }

            if (lhs.getKind() == TypeKind.DECLARED && rhs.getKind() == TypeKind.DECLARED) {
                return isSubtypeTypeArguments( (AnnotatedDeclaredType) rhs, (AnnotatedDeclaredType) lhs );

            } else if (lhs.getKind() == TypeKind.ARRAY && rhs.getKind() == TypeKind.ARRAY) {

                // arrays components within type arguments are invariants too
                // List<String[]> is not a subtype of List<Object[]>
                return isSubtypeAsTypeArgument(((AnnotatedArrayType) rhs).getComponentType(),
                        ((AnnotatedArrayType) lhs).getComponentType());
            }

            return true;
        }
    }

    /**
     * A qualifier hierarchy that generates constraints rather than evaluating them.  Calls to isSubtype
     * generates subtype and equality constraints between the input types based on the expected subtype
     * relationship (as described by the method signature).
     */
    private class InferenceQualifierHierarchy extends MultiGraphQualifierHierarchy {
        final InferenceMain inferenceMain = InferenceMain.getInstance();

        public InferenceQualifierHierarchy(final MultiGraphFactory multiGraphFactory) {
            super(multiGraphFactory);
        }

        public boolean isSubtype(final Collection<? extends AnnotationMirror> rhsAnnos,
                                 final Collection<? extends AnnotationMirror> lhsAnnos ) {
            assert rhsAnnos.size() == 1 && lhsAnnos.size() == 1 : "All types should have exactly 1 annotation!" +
                    "Annotations Types: rhs ( " + InferenceUtil.join(rhsAnnos) + " ) " +
                                       "lhs ( " + InferenceUtil.join(lhsAnnos) + " )\n" +
                    "currentPath: ( " +  InferenceAnnotatedTypeFactory.this.visitorState.getPath() + " )";

            return isSubtype(rhsAnnos.iterator().next(), lhsAnnos.iterator().next());
        }

        public boolean isSubtype(final AnnotationMirror subtype, final AnnotationMirror supertype) {
            final SlotManager slotMgr = inferenceMain.getSlotManager();
            final ConstraintManager constrainMgr = inferenceMain.getConstraintManager();

            final Slot subSlot   = slotMgr.getSlot(subtype);
            final Slot superSlot = slotMgr.getSlot(supertype);
            if (!inferenceMain.isPerformingFlow()) {
                constrainMgr.add(new SubtypeConstraint(subSlot, superSlot));

            }

            return true;
        }

        public AnnotationMirror leastUpperBound(final AnnotationMirror a1, final AnnotationMirror a2) {
            assert a1 != null && a2 != null : "leastUpperBound accepts only NonNull types! a1 (" + a1 + " ) a2 (" + a2 +
                    "currentPath: ( " +  InferenceAnnotatedTypeFactory.this.visitorState.getPath() + " )";

            final SlotManager slotMgr = inferenceMain.getSlotManager();
            final ConstraintManager constraintMgr = inferenceMain.getConstraintManager();
            if(inferenceMain.isPerformingFlow()) {
                //TODO: How to get the path to the CombVariable?
                final Slot slot1 = slotMgr.getSlot(a1);
                final Slot slot2 = slotMgr.getSlot(a2);
                final CombVariableSlot combVariableSlot = new CombVariableSlot(null, slotMgr.nextId(), slot1, slot2);
                slotMgr.addVariable( combVariableSlot );

                constraintMgr.add(new SubtypeConstraint(slot1, combVariableSlot));
                constraintMgr.add(new SubtypeConstraint(slot2, combVariableSlot));

                return slotMgr.getAnnotation(combVariableSlot);
            } else {
                return super.leastUpperBound(a1, a2);
            }
        }
    }


}

