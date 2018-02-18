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
import wyc.lang.WhileyFile.SemanticType.Atom;
import wyc.lang.WhileyFile.SemanticType.Reference;
import wyil.type.subtyping.SubtypeOperator;

public class TypeReferenceExtractor extends AbstractTypeExtractor<SemanticType.Reference> {

	public TypeReferenceExtractor(NameResolver resolver, SubtypeOperator subtypeOperator) {
		super(resolver, subtypeOperator);
	}

	@Override
	protected SemanticType.Reference construct(Atom type) {
		if(type instanceof SemanticType.Reference) {
			return (SemanticType.Reference) type;
		} else {
			return null;
		}
	}

	@Override
	protected SemanticType.Reference union(Reference lhs, Reference rhs) {
		return new SemanticType.Reference(intersectionHelper(lhs.getElement(),rhs.getElement()));
	}

	@Override
	protected SemanticType.Reference intersect(Reference lhs, Reference rhs) {
		return new SemanticType.Reference(intersectionHelper(lhs.getElement(), rhs.getElement()));
	}

	@Override
	protected SemanticType.Reference subtract(Reference lhs, Reference rhs) {
		return new SemanticType.Reference(new SemanticType.Difference(lhs.getElement(),rhs.getElement()));
	}

}
