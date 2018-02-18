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
package wyil.type.util;

import wybs.lang.NameResolver;
import wyc.lang.WhileyFile.SemanticType;
import wyc.lang.WhileyFile.SemanticType.Array;
import wyc.lang.WhileyFile.SemanticType.Atom;
import wyil.type.subtyping.SubtypeOperator;

public class TypeArrayExtractor extends AbstractTypeExtractor<SemanticType.Array> {

	public TypeArrayExtractor(NameResolver resolver, SubtypeOperator subtypeOperator) {
		super(resolver, subtypeOperator);
	}

	@Override
	protected SemanticType.Array construct(Atom type) {
		if(type instanceof SemanticType.Array) {
			return (SemanticType.Array) type;
		} else {
			return null;
		}
	}

	@Override
	protected SemanticType.Array union(SemanticType.Array lhs, SemanticType.Array rhs) {
		return new SemanticType.Array(unionHelper(lhs.getElement(), rhs.getElement()));
	}

	@Override
	protected SemanticType.Array intersect(SemanticType.Array lhs, SemanticType.Array rhs) {
		return new SemanticType.Array(new SemanticType.Difference(lhs.getElement(),rhs.getElement()));
	}

	@Override
	protected SemanticType.Array subtract(SemanticType.Array lhs, SemanticType.Array rhs) {
		return new SemanticType.Array(intersectionHelper(lhs.getElement(), rhs.getElement()));
	}

}
