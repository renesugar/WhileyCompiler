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

		public Type getType() {
			return leaf;
		}
	}

	public static class Reference extends SemanticType {
		private final SemanticType element;

		public Reference(SemanticType element) {
			this.element = element;
		}

		public SemanticType getElement() {
			return element;
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

		public SemanticType getElement() {
			return element;
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
}
