package wyil.type.refinement;

import static wyc.lang.WhileyFile.*;

import java.util.Arrays;

import wybs.lang.NameResolver.ResolutionError;
import wybs.lang.SyntaxError.InternalFailure;
import wybs.lang.CompilationUnit;
import wybs.lang.SyntacticItem;
import wybs.util.AbstractCompilationUnit.Tuple;
import wyc.lang.WhileyFile.Decl;
import wyc.lang.WhileyFile.Type;
import wyc.lang.WhileyFile.Decl.Variable;
import wycc.util.ArrayUtils;
import wyil.type.TypeRefinement;
import wyil.type.TypeSystem;

public class PositiveTypeRefinement implements TypeRefinement {
	private final TypeSystem typeSystem;

	public PositiveTypeRefinement(TypeSystem typeSystem) {
		this.typeSystem = typeSystem;
	}

	@Override
	public Type refine(Type concrete, Type refinement) {
		try {
			final int concreteOpcode = concrete.getOpcode();
			final int refinementOpcode = refinement.getOpcode();
			// First, handle the quick cases
			if (concreteOpcode == refinementOpcode) {
				switch (concrete.getOpcode()) {
				case TYPE_void:
				case TYPE_any:
				case TYPE_null:
				case TYPE_bool:
				case TYPE_byte:
				case TYPE_int:
					return concrete;
				case TYPE_array:
					return refineArray((Type.Array) concrete, (Type.Array) refinement);
				case TYPE_record:
					return refineRecord((Type.Record) concrete, (Type.Record) refinement);
				case TYPE_reference:
					return refineReference((Type.Reference) concrete, (Type.Reference) refinement);
				case TYPE_function:
				case TYPE_method:
				case TYPE_property:
					return refineCallable((Type.Callable) concrete, (Type.Callable) refinement);
				case TYPE_nominal:
					return refineNominal((Type.Nominal) concrete, refinement);
				case TYPE_union:
					return refineUnion((Type.Union) concrete, refinement);
				default:
					internalFailure("unreachable code reached",concrete);
				}
			}
			// Complete examination of concrete type
			switch(concreteOpcode) {
			case TYPE_nominal:
				return refineNominal((Type.Nominal) concrete, refinement);
			case TYPE_union:
				return refineUnion((Type.Union) concrete, refinement);
			}
			// Complete examination of refinement type
			switch(refinementOpcode) {
			case TYPE_nominal:
				return refineNominal(concrete, (Type.Nominal) refinement);
			case TYPE_union:
				return refineUnion( concrete, (Type.Union) refinement);
			}
			// Failed!
			return Type.Void;
		} catch (ResolutionError e) {
			throw new IllegalArgumentException("invalid nominal type", e);
		}
	}

	/**
	 * Refine a nominal type against an arbitrary refinement. For example, the
	 * refinement <code>nint is int</code> where <code>nint</code> is
	 * <code>int|null</code>. In this case, we just expand the nominal type
	 * accordingly.
	 *
	 * @param concrete
	 * @param refinement
	 * @return
	 * @throws ResolutionError
	 */
	private Type refineNominal(Type.Nominal concrete, Type refinement) throws ResolutionError {
		Type.Nominal t = (Type.Nominal) concrete;
		Decl.Type decl = typeSystem.resolveExactly(t.getName(), Decl.Type.class);
		Type type = refine(decl.getType(), refinement);
		// Check whether we can retain nominal information
		return type == decl.getType() ? concrete : type;
	}

	/**
	 * Refine an arbitrary type against an nominal refinement. For example, the
	 * refinement <code>int|null is nat</code> where <code>nat</code> is
	 * a constrained <code>int</code>.
	 *
	 * @param concrete
	 * @param refinement
	 * @return
	 * @throws ResolutionError
	 */
	private Type refineNominal(Type concrete, Type.Nominal refinement) throws ResolutionError {
		Type.Nominal t = (Type.Nominal) refinement;
		Decl.Type decl = typeSystem.resolveExactly(t.getName(), Decl.Type.class);
		return refine(concrete, decl.getType());
	}

	/**
	 * Refine a union type against an arbitrary refinement. For example, the
	 * refinement <code>int|null is int</code>. The key here is that we attempt to
	 * refine each case in turn, and combine what's left (if anything together).
	 *
	 * @param concrete
	 * @param refinement
	 * @return
	 * @throws ResolutionError
	 */
	private Type refineUnion(Type.Union concrete, Type refinement) throws ResolutionError {
		Type.Union t = (Type.Union) concrete;
		Type[] originalBounds = t.getAll();
		Type[] bounds = originalBounds;
		// The difficult with this loop is ensuring that we know when nothing has
		// changed and, in such case, not allocating anything unnecessarily.
		for (int i = 0; i != originalBounds.length; ++i) {
			Type before = originalBounds[i];
			Type after = refine(before, refinement);
			if(before != after) {
				// A concrete refinement has occurred.
				if(bounds == originalBounds) {
					// This is the first time it's happened, therefore we need a new array.
					bounds = Arrays.copyOf(originalBounds, originalBounds.length);
				}
				bounds[i] = after;
			}
		}
		// Check whether anything actually changed
		if(bounds == originalBounds) {
			// No, nothing changed. Therefore, we must honour our contract by returning the
			// original concrete type untouched.
			return concrete;
		} else {
			// Yes, something changed. Therefore, we need to create a new type to represent
			// this.
			bounds = ArrayUtils.removeAll(bounds, Type.Void);
			switch(bounds.length) {
			case 0:
				return Type.Void;
			case 1:
				return bounds[0];
			default:
				return new Type.Union(bounds);
			}
		}

	}

	/**
	 * Refine an arbitrary type against a union refinement. For example, the
	 * refinement <code>int|null|bool is int|bool</code>.
	 *
	 * @param concrete
	 * @param refinement
	 * @return
	 * @throws ResolutionError
	 */
	private Type refineUnion(Type concrete, Type.Union refinement) throws ResolutionError {
		// At this point, we know the concrete type is neither a union nor a nominal.  Example cases:
		// null is null|int
		// {int|null x, int y} is null|{int x}|{int x, int y}
		for(int i=0;i!=refinement.size();++i) {
			Type r = refine(concrete,refinement.get(i));
			if(r != Type.Void) {
				// FIXME: this is clearly broken since there can in principle be multiple
				// refinements for a given type.
				return r;
			}
		}
		return Type.Void;
	}

	/**
	 * Refine a given array type against a given array refinement. For example,
	 * <code>(int|null)[] is int[]</code>. In this case, we simply refine the
	 * element type by recursively computing <code>int|null is int</code>.
	 *
	 * @param concrete
	 * @param refinement
	 * @return
	 * @throws ResolutionError
	 */
	private Type refineArray(Type.Array concrete, Type.Array refinement) throws ResolutionError {
		Type before = concrete.getElement();
		Type after = refine(before, refinement.getElement());
		if (before == after || after == Type.Void) {
			return after;
		} else {
			return new Type.Array(after);
		}
	}

	private Type refineRecord(Type.Record concrete, Type.Record refinement) throws ResolutionError {
		Tuple<Variable> fields = concrete.getFields();
		Variable[] nFields = new Variable[fields.size()];
		if(refinement.getFields().size() != fields.size()) {
			// Indicates discrepancy between number of fields in refinement and concrete type.  Therefore, abort.
			return Type.Void;
		}
		for(int i=0;i!=fields.size();++i) {
			Variable concreteField = fields.get(i);
			Type refinementField = refinement.getField(concreteField.getName());
			if(refinementField == null) {
				// Indicates no corresponding field in refinement, therefore abort.
				return Type.Void;
			}
			Type refinedFieldType = refine(concreteField.getType(),refinementField);
			if(refinedFieldType == Type.Void) {
				return Type.Void;
			} else {
				nFields[i] = new Decl.Variable(concreteField.getModifiers(), concreteField.getName(), refinedFieldType);
			}
		}
		// FIXME: need to deal with open records
		return new Type.Record(concrete.isOpen(), new Tuple<>(nFields));
	}

	private Type refineReference(Type.Reference concrete, Type.Reference refinement) throws ResolutionError {
		// FIXME: Need to thread through lifetimes.eq
		boolean l2r = typeSystem.isRawCoerciveSubtype(concrete, refinement, null);
		boolean r2l = typeSystem.isRawCoerciveSubtype(refinement, concrete, null);
		if(l2r && r2l) {
			return concrete;
		} else {
			return Type.Void;
		}
	}

	private Type refineCallable(Type.Callable concrete, Type.Callable refinement) {
		throw new RuntimeException("Implement me!");
	}

	private <T> T internalFailure(String msg, SyntacticItem e) {
		// FIXME: this is a kludge
		CompilationUnit cu = (CompilationUnit) e.getHeap();
		throw new InternalFailure(msg, cu.getEntry(), e);
	}

}
