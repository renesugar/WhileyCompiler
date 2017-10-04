// Copyright 2011 The Whiley Project Developers
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package wyil.type.extractors;

import static wyc.lang.WhileyFile.*;

import wybs.lang.NameResolver;
import wybs.lang.NameResolver.ResolutionError;
import wyc.lang.WhileyFile.Type;
import wyc.lang.WhileyFile.Type.Atom;
import wyil.type.SubtypeOperator.LifetimeRelation;
import wyil.type.TypeSystem;

/**
 * <p>
 * Responsible for extracting the <i>representation type</i> from a given type
 * <code>T</code>. This is the smallest <i>simple type</i> <code>S</code> where
 * <code>S :> T</code>. In other words, it is the smallest simple type which can
 * hold values of <code>T</code> (and, in many cases, <code>T = S</code>). Here,
 * a simple type is one which does not involve intersections or negations.
 * Simple types are important as they are used exclusively in WyLL.
 * </p>
 * <p>
 * As an example to illustrate, consider this Whiley code:
 * </p>
 *
 * <pre>
 * function f(int|null x) -> (int r):
 *    if x is int:
 *       return x
 *    else:
 *       return 0
 * </pre>
 *
 * <p>
 * Here, the type of x on the true branch is <code>(int|null)&int)</code>. This
 * can easily be simplified to <code>(int&int)|(null&int)</code> and then to its
 * representation type <code>int</code>. Likewise, on the false branch we have
 * <code>(int|null)&!int</code> which simplifies to
 * <code>(int&!int)|(null&!int)</code> and then to its representation type
 * <code>null</code>.
 * </p>
 * <p>
 * In the above example, the representation type was simply the simplified type.
 * However, this is not always the case. For example, consider this variant on
 * the above:
 * </p>
 *
 * <pre>
 * function f(any x) -> (int r):
 *    if x is int:
 *       return x
 *    else:
 *       return 0
 * </pre>
 *
 * <p>
 * As before, the representation type of <code>x</code> on the true branch is
 * <code>any&int</code> which gives <code>int</code>. However, the type of
 * <code>x</code> on the false branch is determined as <code>any&!int</code>.
 * Since this type cannot be simplified further, we need to find the small type
 * enclosing it for the representation type (which, in this case, is
 * <code>any</code>).
 * </p>
 *
 * @author David J. Pearce
 *
 */
public class RepresentationTypeExtractor extends AbstractTypeExtractor<Type> {

	public RepresentationTypeExtractor(NameResolver resolver, TypeSystem typeSystem) {
		super(resolver, typeSystem);
	}

	@Override
	public Type extract(Type type, LifetimeRelation lifetimes, Object supplementary) throws ResolutionError {
		return super.extract(type, lifetimes, supplementary);
	}

	@Override
	protected Type construct(Atom type) {
		return type;
	}

	@Override
	protected Type union(Type lhs, Type rhs) {
		// FIXME: This feels to me as though it is broken because it loses nesting
		// information. On the backends, this nesting information is important because
		// it determines the arrangement of type tags. At the same time, we need to
		// combine these things together. One solution here is to generalise this method
		// into disjuncting arbitrary elements.
		return unionHelper(lhs,rhs);
	}

	@Override
	protected Type intersect(Type lhs, Type rhs) {
		int opcode = lhs.getOpcode();
		if (opcode == rhs.getOpcode()) {
			switch (opcode) {
			case TYPE_any:
			case TYPE_void:
			case TYPE_null:
			case TYPE_bool:
			case TYPE_byte:
				return lhs;
			case TYPE_int:
				return intersect((Type.Int) lhs, (Type.Int) rhs);
			case TYPE_record:
				return intersect((Type.Record) lhs, (Type.Record) rhs);
			case TYPE_array:
				return intersect((Type.Array) lhs, (Type.Array) rhs);
			case TYPE_reference:
				return intersect((Type.Reference) lhs, (Type.Reference) rhs);
			case TYPE_function:
			case TYPE_method:
			case TYPE_property:
				return intersect((Type.Callable) lhs, (Type.Callable) rhs);
			}
		}
		return Type.Void;
	}

	@Override
	protected Type subtract(Type lhs, Type rhs) {
		return lhs;
	}

	// ============================================================
	// Integers
	// ============================================================

	protected Type intersect(Type.Int lhs, Type.Int rhs) {
		// FIXME: this is broken in the case of finitely sized integer types.
		return lhs;
	}

	// ============================================================
	// Arrays
	// ============================================================

	protected Type.Array intersect(Type.Array lhs, Type.Array rhs) {
		return new Type.Array(intersectionHelper(lhs.getElement(), rhs.getElement()));
	}

	// ============================================================
	// References
	// ============================================================

	protected Type.Reference intersect(Type.Reference lhs, Type.Reference rhs) {
		return new Type.Reference(intersectionHelper(lhs.getElement(), rhs.getElement()));
	}

	// ============================================================
	// Lambdas
	// ============================================================

	protected Type intersect(Type.Callable lhs, Type.Callable rhs) {
		// FIXME: this is obviously broken.
		return Type.Void;
	}
}
