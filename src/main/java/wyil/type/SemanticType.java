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
import wybs.util.AbstractCompilationUnit;
import wybs.util.AbstractSyntacticItem;
import wycc.util.ArrayUtils;
import wycc.util.Pair;

public abstract class SemanticType {
	public static final SemanticType Void = new SemanticType.Leaf(Type.Void);
	public static final SemanticType Null = new SemanticType.Leaf(Type.Null);
	public static final SemanticType Bool = new SemanticType.Leaf(Type.Bool);
	public static final SemanticType Byte = new SemanticType.Leaf(Type.Byte);
	public static final SemanticType Int = new SemanticType.Leaf(Type.Int);

	public SemanticType.Array asArray() {
		return null;
	}

	public SemanticType.Record asRecord() {
		return null;
	}

	public SemanticType.Reference asReference() {
		return null;
	}

	public Type.Callable asCallable() {
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
		private final Identifier lifetime;

		public Reference(SemanticType element) {
			this.element = element;
			this.lifetime = null;
		}

		public Reference(SemanticType element, Identifier lifetime) {
			this.element = element;
			this.lifetime = lifetime;
		}

		public SemanticType getElement() {
			return element;
		}

		public Identifier getLifetime() {
			return lifetime;
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

	public static class Field {
		private final Identifier name;
		private final SemanticType type;

		public Field(Identifier name, SemanticType type) {
			this.name = name;
			this.type = type;
		}

		public Identifier getName() {
			return name;
		}

		public SemanticType getType() {
			return type;
		}
	}

	public static class Record extends SemanticType {
		private final Field[] fields;
		private final boolean isOpen;

		public Record(boolean isOpen, Field... fields) {
			this.isOpen = isOpen;
			this.fields = fields;
		}

		public Field[] getFields() {
			return fields;
		}

		public SemanticType getField(Identifier name) {
			for(int i=0;i!=fields.length;++i) {
				Field field = fields[i];
				if(field.getName().equals(name)) {
					return field.getType();
				}
			}
			return null;
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

	public static SemanticType toSemanticType(Type type) {
		return new SemanticType.Leaf(type);
	}
}
