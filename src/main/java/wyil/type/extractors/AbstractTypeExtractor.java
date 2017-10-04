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

import java.util.ArrayList;
import java.util.Arrays;

import static wyc.lang.WhileyFile.Type;
import static wyc.lang.WhileyFile.Decl;
import wybs.lang.NameResolver;
import wybs.lang.NameResolver.ResolutionError;
import wybs.util.AbstractCompilationUnit.Identifier;
import wybs.util.AbstractCompilationUnit.Tuple;
import wycc.util.ArrayUtils;
import wyil.type.SubtypeOperator.LifetimeRelation;
import wyil.type.TypeExtractor;
import wyil.type.TypeSystem;

/**
 * <p>
 * A generic foundation for "convential" type extractors. That is, those which
 * extract type information from types (rather than, for example, information
 * about type invariants, etc). The approach consists of two phases:
 * </p>
 *
 * <ol>
 * <li><b>(Conversion)</b>. First we convert the given type into <i>Disjunctive
 * Normal Form (DNF)</i>. That is a type with a specific structure made up from
 * one or more "conjuncts" which are disjuncted together. Each conjunct contains
 * one or more signed atoms which are intersected. For example,
 * "<code>int&(null|bool)</code>" is converted into
 * "<code>(int&null)|(int&bool)</code>".</li>
 * <li><b>(Construction)</b>. Second, we attempt to construct the DNF type into
 * an instance of the target type. This requires that all types in a given
 * conjunct match the given target type (or are negations thereof). One
 * exception, however, is when a given conjunct gives a contradiction (i.e.
 * reduces to void). Such conjuncts can be safely ignored. For example, consider
 * the type "<code>!null & (null|{int f})</code>". This expands to the conjuncts
 * "<code>!null & null</code>" and "<code>(!null)&{int f}</code>". Since the
 * former reduces to "<code>void</code>", we are left with
 * "<code>(!null)&{int f}</code>" which generates the extraction
 * "<code>{int f}</code>".</li>
 * </ol>
 *
 * <p>
 * During construction the concrete subclass is called to try and construct an
 * instance of the target type from a given atom. This gives the subclass the
 * ability to determine what the target type is. Furthermore, the subclass must
 * provide appropriate methods for combining instances of the target type though
 * <i>union</i> , <i>intersection</i> and <i>subtraction</i> operators.
 * </p>
 * <p>
 * For further reading on the operation of this algorithm, the following is
 * suggested:
 * <ul>
 * <li><b>Sound and Complete Flow Typing with Unions, Intersections and
 * Negations</b>. David J. Pearce. In Proceedings of the Conference on
 * Verification, Model Checking, and Abstract Interpretation (VMCAI), volume
 * 7737 of Lecture Notes in Computer Science, pages 335--354, 2013.</li>
 * </ul>
 * </p>
 *
 * @author David J. Pearce
 *
 * @param <T>
 */
public abstract class AbstractTypeExtractor<T extends Type> implements TypeExtractor<T,Object> {
	protected final NameResolver resolver;
	protected final TypeSystem typeSystem;

	public AbstractTypeExtractor(NameResolver resolver, TypeSystem typeSystem) {
		this.resolver = resolver;
		this.typeSystem = typeSystem;
	}

	@Override
	public T extract(Type type, LifetimeRelation lifetimes, Object supplementary) throws ResolutionError {
		Disjunct dnf = toDisjunctiveNormalForm(type);
		return construct(dnf, lifetimes);
	}

	/**
	 * Convert an arbitrary type to <i>Disjunctive Normal Form (DNF)</i>. That
	 * is a type with a specific structure made up from one or more "conjuncts"
	 * which are unioned together. Each conjunct contains one or more signed
	 * atoms which are intersected. For example, <code>int&(null|bool)</code> is
	 * converted into <code>(int&null)|(int&bool)</code>.
	 *
	 * @param type
	 * @param atoms
	 * @return
	 * @throws ResolutionError
	 */
	protected Disjunct toDisjunctiveNormalForm(Type type) throws ResolutionError {
		if(type == null) {
			throw new IllegalArgumentException("type to extract cannot be null");
		} else if (type instanceof Type.Primitive) {
			return toDisjunctiveNormalForm((Type.Primitive) type);
		} else if(type instanceof Type.Array) {
			return toDisjunctiveNormalForm((Type.Array) type);
		} else if (type instanceof Type.Reference) {
			return toDisjunctiveNormalForm((Type.Reference) type);
		} else if (type instanceof Type.Record) {
			return toDisjunctiveNormalForm((Type.Record) type);
		} else if (type instanceof Type.Callable) {
			return toDisjunctiveNormalForm((Type.Callable) type);
		} else if (type instanceof Type.Difference) {
			return toDisjunctiveNormalForm((Type.Difference) type);
		} else if (type instanceof Type.Nominal) {
			return toDisjunctiveNormalForm((Type.Nominal) type);
		} else if (type instanceof Type.Union) {
			return toDisjunctiveNormalForm((Type.Union) type);
		} else {
			return toDisjunctiveNormalForm((Type.Intersection) type);
		}
	}

	protected Disjunct toDisjunctiveNormalForm(Type.Primitive type) throws ResolutionError {
		return new Disjunct((Type.Atom) type);
	}

	protected Disjunct toDisjunctiveNormalForm(Type.Record type) throws ResolutionError {
		return new Disjunct(type);
	}

	protected Disjunct toDisjunctiveNormalForm(Type.Reference type) throws ResolutionError {
		return new Disjunct(type);
	}

	protected Disjunct toDisjunctiveNormalForm(Type.Array type) throws ResolutionError {
		return new Disjunct(type);
	}

	protected Disjunct toDisjunctiveNormalForm(Type.Callable type) throws ResolutionError {
		return new Disjunct(type);
	}

	protected Disjunct toDisjunctiveNormalForm(Type.Difference type) throws ResolutionError {
		Disjunct lhs = toDisjunctiveNormalForm(type.getLeftHandSide());
		Disjunct rhs = toDisjunctiveNormalForm(type.getRightHandSide());
		return lhs.intersect(rhs.negate());
	}

	protected Disjunct toDisjunctiveNormalForm(Type.Union type) throws ResolutionError {
		Disjunct result = null;
		//
		for (int i = 0; i != type.size(); ++i) {
			Disjunct child = toDisjunctiveNormalForm(type.get(i));
			if(result == null) {
				result = child;
			} else {
				result = result.union(child);
			}
		}
		//
		return result;
	}

	protected  Disjunct toDisjunctiveNormalForm(Type.Intersection type) throws ResolutionError {
		Disjunct result = null;
		//
		for (int i = 0; i != type.size(); ++i) {
			Disjunct child = toDisjunctiveNormalForm(type.get(i));
			if(result == null) {
				result = child;
			} else {
				result = result.intersect(child);
			}
		}
		//
		return result;
	}

	protected Disjunct toDisjunctiveNormalForm(Type.Nominal nominal) throws ResolutionError {
		Decl.Type decl = resolver.resolveExactly(nominal.getName(),Decl.Type.class);
		return toDisjunctiveNormalForm(decl.getVariableDeclaration().getType());
	}

	/**
	 * Construct a given target type from a given type in DisjunctiveNormalForm.
	 *
	 * @param type
	 * @return
	 * @throws ResolutionError
	 */
	protected T construct(Disjunct type, LifetimeRelation lifetimes) throws ResolutionError {
		T result = null;
		Conjunct[] conjuncts = type.conjuncts;
		for(int i=0;i!=conjuncts.length;++i) {
			Conjunct conjunct = conjuncts[i];
			if(!isVoid(conjunct, lifetimes)) {
				T tmp = construct(conjunct);
				if(tmp == null) {
					// This indicates one of the conjuncts did not generate a proper
					// extraction. At which point, there is no possible extraction
					// and we can terminate early.
					return null;
				} else if(result == null) {
					result = tmp;
				} else {
					result = union(result,tmp);
				}
			}
		}
		return result;
	}

	/**
	 * Determine whether a given conjunct is equivalent to <code>void</code> or
	 * not. For example, <code>int & !int</code> is equivalent to
	 * <code>void</code>. Likewise, <code>{int f} & !{any f}</code> is
	 * equivalent to <code>void</code>.
	 *
	 * @param type
	 * @return
	 * @throws ResolutionError
	 */
	protected boolean isVoid(Conjunct type, LifetimeRelation lifetimes) throws ResolutionError {
		// FIXME: I believe we could potentially be more efficient here. In
		// particular, when Type.Union and Type.Intersection are interfaces, we
		// can make Disjunct and Conjunct implement them, thus avoiding this
		// unnecessary copying of data.
		Type.Atom[] positives = Arrays.copyOf(type.positives,type.positives.length);
		Type.Atom[] negatives = Arrays.copyOf(type.negatives,type.negatives.length);
		Type.Intersection lhs = new Type.Intersection(positives);
		Type.Union rhs = new Type.Union(negatives);
		//
		return typeSystem.isVoid(new Type.Difference(lhs,rhs), lifetimes);
	}

	protected T construct(Conjunct type) {
		T result = null;
		// First, combine the positive terms together
		Type.Atom[] positives = type.positives;
		for (int i = 0; i != positives.length; ++i) {
			Type.Atom pos = positives[i];
			T tmp = construct(pos);
			if (tmp == null) {
				return null;
			} else if (result == null) {
				result = tmp;
			} else {
				result = intersect(result, tmp);
			}
		}
		if (result != null) {
			// Second, subtract all the negative types
			Type.Atom[] negatives = type.negatives;
			for (int i = 0; i != negatives.length; ++i) {
				T tmp = construct(negatives[i]);
				if (tmp != null) {
					result = subtract(result, tmp);
				}
			}
		}
		return result;
	}

	protected abstract T construct(Type.Atom type);

	/**
	 * Union two canonical conjuncts together
	 *
	 * @param lhs
	 * @param rhs
	 * @return
	 */
	protected abstract T union(T lhs, T rhs);

	/**
	 * Intersect two positive atoms together
	 * @param lhs
	 * @param rhs
	 * @return
	 */
	protected abstract T intersect(T lhs, T rhs);

	/**
	 * Subtract one position atom from another.
	 *
	 * @param lhs
	 * @param rhs
	 * @return
	 */
	protected abstract T subtract(T lhs, T rhs);

	// ==========================================================
	// Common record operators. These are included because they are reused, and yet
	// are also quite complex.
	// ==========================================================

	protected Type.Record subtract(Type.Record lhs, Type.Record rhs) {
		ArrayList<Decl.Variable> fields = new ArrayList<>();
		Tuple<Decl.Variable> lhsFields = lhs.getFields();
		Tuple<Decl.Variable> rhsFields = rhs.getFields();
		for (int i = 0; i != lhsFields.size(); ++i) {
			for (int j = 0; j != rhsFields.size(); ++j) {
				Decl.Variable lhsField = lhsFields.get(i);
				Decl.Variable rhsField = rhsFields.get(j);
				Identifier lhsFieldName = lhsField.getName();
				Identifier rhsFieldName = rhsField.getName();
				if (lhsFieldName.equals(rhsFieldName)) {
					// FIXME: could potentially do better here
					Type negatedRhsType = new Type.Negation(rhsField.getType());
					Type type = intersectionHelper(lhsField.getType(), negatedRhsType);
					fields.add(new Decl.Variable(new Tuple<>(), lhsFieldName, type));
					break;
				}
			}
		}
		if(fields.size() != lhsFields.size()) {
			// FIXME: need to handle the case of open records here.
			return lhs;
		} else {
			return new Type.Record(lhs.isOpen(), new Tuple<>(fields));
		}
	}

	protected Type.Record intersect(Type.Record lhs, Type.Record rhs) {
		//
		Tuple<Decl.Variable> lhsFields = lhs.getFields();
		Tuple<Decl.Variable> rhsFields = rhs.getFields();
		// Determine the number of matching fields. That is, fields with the
		// same name.
		int matches = countMatchingFields(lhsFields, rhsFields);
		// When intersecting two records, the number of fields is only
		// allowed to differ if one of them is an open record. Therefore, we
		// need to pay careful attention to the size of the resulting match
		// in comparison with the original records.
		if (matches < lhsFields.size() && !rhs.isOpen()) {
			// Not enough matches made to meet the requirements of the lhs
			// type.
			return null;
		} else if (matches < rhsFields.size() && !lhs.isOpen()) {
			// Not enough matches made to meet the requirements of the rhs
			// type.
			return null;
		} else {
			// At this point, we know the intersection succeeds. The next
			// job is to determine the final set of field declarations.
			int lhsRemainder = lhsFields.size() - matches;
			int rhsRemainder = rhsFields.size() - matches;
			Decl.Variable[] fields = new Decl.Variable[matches + lhsRemainder + rhsRemainder];
			// Extract all matching fields first
			int index = extractMatchingFields(lhsFields, rhsFields, fields);
			// Extract remaining lhs fields second
			index = extractNonMatchingFields(lhsFields, rhsFields, fields, index);
			// Extract remaining rhs fields last
			index = extractNonMatchingFields(rhsFields, lhsFields, fields, index);
			// The intersection of two records can only be open when both
			// are themselves open.
			boolean isOpen = lhs.isOpen() && rhs.isOpen();
			//
			return new Type.Record(isOpen, new Tuple<>(fields));
		}
	}

	/**
	 * Count the number of matching fields. That is, fields with the same name.
	 *
	 * @param lhsFields
	 * @param rhsFields
	 * @return
	 */
	protected int countMatchingFields(Tuple<Decl.Variable> lhsFields, Tuple<Decl.Variable> rhsFields) {
		int count = 0;
		for (int i = 0; i != lhsFields.size(); ++i) {
			for (int j = 0; j != rhsFields.size(); ++j) {
				Decl.Variable lhsField = lhsFields.get(i);
				Decl.Variable rhsField = rhsFields.get(j);
				Identifier lhsFieldName = lhsField.getName();
				Identifier rhsFieldName = rhsField.getName();
				if (lhsFieldName.equals(rhsFieldName)) {
					count = count + 1;
				}
			}
		}
		return count;
	}

	/**
	 * Extract all matching fields (i.e. fields with the same name) into the
	 * result array.
	 *
	 * @param lhsFields
	 * @param rhsFields
	 * @param result
	 * @return
	 */
	protected int extractMatchingFields(Tuple<Decl.Variable> lhsFields, Tuple<Decl.Variable> rhsFields,
			Decl.Variable[] result) {
		int index = 0;
		// Extract all matching fields first.
		for (int i = 0; i != lhsFields.size(); ++i) {
			for (int j = 0; j != rhsFields.size(); ++j) {
				Decl.Variable lhsField = lhsFields.get(i);
				Decl.Variable rhsField = rhsFields.get(j);
				Identifier lhsFieldName = lhsField.getName();
				Identifier rhsFieldName = rhsField.getName();
				if (lhsFieldName.equals(rhsFieldName)) {
					Type type = intersectionHelper(lhsField.getType(), rhsField.getType());
					Decl.Variable combined = new Decl.Variable(new Tuple<>(), lhsFieldName, type);
					result[index++] = combined;
				}
			}
		}
		return index;
	}

	/**
	 * Extract fields from lhs which do not match any field in the rhs. That is,
	 * there is no field in the rhs with the same name.
	 *
	 * @param lhsFields
	 * @param rhsFields
	 * @param result
	 * @param index
	 * @return
	 */
	protected int extractNonMatchingFields(Tuple<Decl.Variable> lhsFields, Tuple<Decl.Variable> rhsFields,
			Decl.Variable[] result, int index) {
		outer: for (int i = 0; i != lhsFields.size(); ++i) {
			for (int j = 0; j != rhsFields.size(); ++j) {
				Decl.Variable lhsField = lhsFields.get(i);
				Decl.Variable rhsField = rhsFields.get(j);
				Identifier lhsFieldName = lhsField.getName();
				Identifier rhsFieldName = rhsField.getName();
				if (lhsFieldName.equals(rhsFieldName)) {
					// This is a matching field. Therefore, continue on to the
					// next lhs field
					continue outer;
				}
			}
			result[index++] = lhsFields.get(i);
		}
		return index;
	}

	// ==========================================================

	/**
	 * Provides a simplistic form of type union which, in some cases, does
	 * slightly better than simply creating a new union. For example, unioning
	 * <code>int</code> with <code>int</code> will return <code>int</code>
	 * rather than <code>int|int</code>.
	 *
	 * @param lhs
	 * @param rhs
	 * @return
	 */
	protected Type unionHelper(Type lhs, Type rhs) {
		if(lhs.equals(rhs)) {
			return lhs;
		} else if(lhs instanceof Type.Void) {
			return rhs;
		} else if(rhs instanceof Type.Void) {
			return lhs;
		} else {
			return new Type.Union(new Type[]{lhs,rhs});
		}
	}

	/**
	 * Provides a simplistic form of type intersect which, in some cases, does
	 * slightly better than simply creating a new intersection. For example,
	 * intersecting <code>int</code> with <code>int</code> will return
	 * <code>int</code> rather than <code>int&int</code>.
	 *
	 * @param lhs
	 * @param rhs
	 * @return
	 */
	protected Type intersectionHelper(Type lhs, Type rhs) {
		if(lhs.equals(rhs)) {
			return lhs;
		} else if(lhs instanceof Type.Void) {
			return lhs;
		} else if(rhs instanceof Type.Void) {
			return rhs;
		} else {
			return new Type.Intersection(new Type[]{lhs,rhs});
		}
	}

	/**
	 * A signed type is simply a type which is either positive or negatively
	 * signed. For example, <code>{int f}</code> is a positively signed record,
	 * whilst <code>!{int f}</code> is negatively signed record.
	 *
	 * @author David J. Pearce
	 *
	 */
	protected static class Signed {
		private final boolean sign;
		private final Type.Atom type;

		public Signed(boolean sign, Type.Atom type) {
			this.sign = sign;
			this.type = type;
		}
		public boolean getSign() {
			return sign;
		}
		public Type.Atom getType() {
			return type;
		}
		public Signed negate() {
			return new Signed(!sign,type);
		}
	}

	protected static class Disjunct {
		private final Conjunct[] conjuncts;

		public Disjunct(Type.Atom atom) {
			conjuncts = new Conjunct[]{new Conjunct(atom)};
		}

		public Disjunct(Conjunct... conjuncts) {
			for(int i=0;i!=conjuncts.length;++i) {
				if(conjuncts[i] == null) {
					throw new IllegalArgumentException("conjuncts cannot contain null");
				}
			}
			this.conjuncts = conjuncts;
		}

		public Disjunct union(Disjunct other) {
			Conjunct[] otherConjuncts = other.conjuncts;
			int length = conjuncts.length + otherConjuncts.length;
			Conjunct[] combinedConjuncts = Arrays.copyOf(conjuncts, length);
			System.arraycopy(otherConjuncts, 0, combinedConjuncts, conjuncts.length, otherConjuncts.length);
			return new Disjunct(combinedConjuncts);
		}

		public Disjunct intersect(Disjunct other) {
			Conjunct[] otherConjuncts = other.conjuncts;
			int length = conjuncts.length * otherConjuncts.length;
			Conjunct[] combinedConjuncts = new Conjunct[length];
			int k = 0;
			for (int i = 0; i != conjuncts.length; ++i) {
				Conjunct ith = conjuncts[i];
				for (int j = 0; j != otherConjuncts.length; ++j) {
					Conjunct jth = otherConjuncts[j];
					combinedConjuncts[k++] = ith.intersect(jth);
				}
			}
			return new Disjunct(combinedConjuncts);
		}

		public Disjunct negate() {
			Disjunct result = null;
			for (int i = 0; i != conjuncts.length; ++i) {
				Disjunct d = conjuncts[i].negate();
				if (result == null) {
					result = d;
				} else {
					result = result.intersect(d);
				}
			}
			return result;
		}

		@Override
		public String toString() {
			String r = "";
			for(int i=0;i!=conjuncts.length;++i) {
				if(i != 0) {
					r += " \\/ ";
				}
				r += conjuncts[i];
			}
			return r;
		}
	}

	protected static class Conjunct {
		private final Type.Atom[] positives;
		private final Type.Atom[] negatives;

		public Conjunct(Type.Atom positive) {
			positives = new Type.Atom[]{positive};
			negatives = new Type.Atom[0];
		}

		public Conjunct(Type.Atom[] positives, Type.Atom[] negatives) {
			this.positives = positives;
			this.negatives = negatives;
		}

		public Conjunct intersect(Conjunct other) {
			Type.Atom[] combinedPositives = ArrayUtils.append(positives, other.positives);
			Type.Atom[] combinedNegatives = ArrayUtils.append(negatives, other.negatives);
			return new Conjunct(combinedPositives,combinedNegatives);
		}

		public Disjunct negate() {
			int length = positives.length + negatives.length;
			Conjunct[] conjuncts = new Conjunct[length];
			for (int i = 0; i != positives.length; ++i) {
				Type.Atom positive = positives[i];
				conjuncts[i] = new Conjunct(EMPTY_ATOMS, new Type.Atom[] { positive });
			}
			for (int i = 0, j = positives.length; i != negatives.length; ++i, ++j) {
				Type.Atom negative = negatives[i];
				conjuncts[j] = new Conjunct(negative);
			}
			return new Disjunct(conjuncts);
		}

		@Override
		public String toString() {
			String r = "(";
			for(int i=0;i!=positives.length;++i) {
				if(i != 0) {
					r += " /\\ ";
				}
				r += positives[i];
			}
			r += ") - (";
			for(int i=0;i!=negatives.length;++i) {
				if(i != 0) {
					r += " \\/ ";
				}
				r += negatives[i];
			}
			return r + ")";
		}
		private static final Type.Atom[] EMPTY_ATOMS = new Type.Atom[0];
	}
}
