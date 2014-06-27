package hardcoded.quals;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.sun.source.tree.Tree;
import org.checkerframework.framework.qual.DefaultQualifierInHierarchy;
import org.checkerframework.framework.qual.ImplicitFor;
import org.checkerframework.framework.qual.SubtypeOf;
import org.checkerframework.framework.qual.TypeQualifier;

import javax.lang.model.type.TypeKind;

/**
 * Represents data that is not hardcoded.
 *
 * @see MaybeHardcoded
 * @see PolyHardcoded
 * @see trusted.quals.Trusted
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
@TypeQualifier
@SubtypeOf({MaybeHardcoded.class})
@ImplicitFor(
        trees={ Tree.Kind.NULL_LITERAL },
        types={ TypeKind.NULL })
public @interface NotHardcoded {}
