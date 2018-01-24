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
package wyil.type;

import wyc.lang.WhileyFile.Decl;
import wyc.lang.WhileyFile.Type;
import static wyc.lang.WhileyFile.TYPE_union;
import static wyc.lang.WhileyFile.TYPE_nominal;
import static wyc.lang.WhileyFile.Identifier;

import java.util.Arrays;

import wybs.lang.NameResolver.ResolutionError;
import wycc.util.ArrayUtils;
import wycc.util.Pair;

public abstract class SemanticType {
	public abstract Disjunct toDisjunctiveNormalForm();

	public SemanticType.Array asArray() {
		return null;
	}

	public SemanticType.Record asRecord() {
		return null;
	}

	public SemanticType.Reference asReference() {
		return null;
	}

	public SemanticType union(SemanticType t) {
		return new Union(this,t);
	}

	public SemanticType intersect(SemanticType t) {
		return null;
	}

	public SemanticType negate(SemanticType t) {
		return null;
	}

	public static class Leaf extends SemanticType {
		private final Type leaf;

		public Leaf(Type leaf) {
			this.leaf = leaf;
		}

		@Override
		public Disjunct toDisjunctiveNormalForm(TypeSystem typeSystem) {
			return toDisjunctiveNormalForm(this,typeSystem);
		}

		public Type getType() {
			return type;
		}

		@Override
		public Array asArray() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Record asRecord() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Reference asReference() {
			// TODO Auto-generated method stub
			return null;
		}
	}

	public static class Reference extends SemanticType {
		private final SemanticType element;

		public Reference(SemanticType element) {
			this.element = element;
		}

		@Override
		public Reference asReference() {
			return this;
		}
	}

	public static class Array extends SemanticType {
		private final SemanticType element;

		public Array(SemanticType element) {
			this.element = element;
		}

		@Override
		public Array asArray() {
			return this;
		}
	}

	public static class Record extends SemanticType {
		private final Pair<Identifier,SemanticType>[] fields;

		public Record(Pair<Identifier,SemanticType>... fields) {
			this.fields = fields;
		}

		@Override
		public Record asRecord() {
			return this;
		}
	}

	public static class Union extends SemanticType {
		private final SemanticType lhs;
		private final SemanticType rhs;

		public Union(SemanticType lhs, SemanticType rhs) {
			this.lhs = lhs;
			this.rhs = rhs;
		}

		public SemanticType getLeftHandSide()  {
			return lhs;
		}

		public SemanticType getRightHandSide()  {
			return rhs;
		}

		@Override
		public Array asArray() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Record asRecord() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Reference asReference() {
			// TODO Auto-generated method stub
			return null;
		}
	}

	public static class Intersection extends SemanticType {
		private final SemanticType lhs;
		private final SemanticType rhs;

		public Intersection(SemanticType lhs, SemanticType rhs) {
			this.lhs = lhs;
			this.rhs = rhs;
		}

		public SemanticType getLeftHandSide()  {
			return lhs;
		}

		public SemanticType getRightHandSide()  {
			return rhs;
		}

		@Override
		public Array asArray() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Record asRecord() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Reference asReference() {
			// TODO Auto-generated method stub
			return null;
		}
	}

	public static class Difference extends SemanticType {
		private final SemanticType lhs;
		private final SemanticType rhs;

		public Difference(SemanticType lhs, SemanticType rhs) {
			this.lhs = lhs;
			this.rhs = rhs;
		}

		public SemanticType getLeftHandSide()  {
			return lhs;
		}

		public SemanticType getRightHandSide()  {
			return rhs;
		}

		@Override
		public Array asArray() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Record asRecord() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Reference asReference() {
			// TODO Auto-generated method stub
			return null;
		}
	}

	public static Disjunct toDisjunctiveNormalForm(Type type, TypeSystem typeSystem) {
		try {
			switch (type.getOpcode()) {
			case TYPE_union: {
				Type.Union t = (Type.Union) type;
				Disjunct r = null;
				for (int i = 0; i != t.size(); ++i) {
					Disjunct ith = toDisjunctiveNormalForm(t.get(i), typeSystem);
					if (r == null) {
						r = ith;
					} else {
						r = r.union(ith);
					}
				}
				return r;
			}
			case TYPE_nominal: {
				Type.Nominal nom = (Type.Nominal) type;
				Decl.Type decl = typeSystem.resolveExactly(nom.getName(), Decl.Type.class);
				// FIXME: need to deal properly with maximisation
				// To do this, we need to somehow encode the fact as to whether or not this is
				// constrained. That should go into the worklist item and replace maximise.
				//
				// if (item.maximise || decl.getInvariant().size() == 0) {
				// worklist.push(item.sign, decl.getType(), item.maximise);
				// } else if (item.sign) {
				// // Corresponds to void, so we're done on this path.
				// return true;
				// }
				return toDisjunctiveNormalForm(decl.getType(), typeSystem);
			}
			default:
				// ASSERT: type instanceof Type.Atom
				return new Disjunct((Type.Atom) type);
			}
		} catch (ResolutionError e) {
			throw new IllegalArgumentException("invalid nominal type encountered", e);
		}
	}

	public static class Disjunct {
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

	public static class Conjunct {
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
