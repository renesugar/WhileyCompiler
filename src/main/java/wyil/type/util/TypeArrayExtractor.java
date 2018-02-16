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

import wyc.lang.WhileyFile.Type;
import static wyc.lang.WhileyFile.*;
import wycc.util.ArrayUtils;

import java.util.function.Function;

import wybs.lang.NameResolver;
import wyc.lang.WhileyFile.Decl;
import wyc.lang.WhileyFile.SemanticType;

/**
 * Extract a <code>Type.Array</code> from an arbitrary
 * <code>SemanticType</code>. This requires that the given
 * <code>SemanticType</code> corresponds exactly to an array.
 *
 * @author David J. Pearce
 *
 */
public class TypeArrayExtractor implements Function<SemanticType, SemanticType.Array> {
	private final NameResolver resolver;

	public TypeArrayExtractor(NameResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	public SemanticType.Array apply(SemanticType type) {
		return extractType(type);
	}

	private SemanticType.Array extractType(SemanticType type) {
		switch(type.getOpcode()) {
		case TYPE_null:
		case TYPE_bool:
		case TYPE_byte:
		case TYPE_int:
		case TYPE_reference:
		case TYPE_record:
		case TYPE_function:
		case TYPE_method:
		case TYPE_property:
		case SEMTYPE_reference:
		case SEMTYPE_record:
			return null;
		case SEMTYPE_array:
		case TYPE_array:
			return (SemanticType.Array) type;
		case TYPE_nominal:
			return extractNominal((Type.Nominal) type);
		case TYPE_union:
			return extractUnion((Type.Union) type);
		case SEMTYPE_union:
			return extractUnion((SemanticType.Union) type);
		case SEMTYPE_intersection:
			return extractIntersection((SemanticType.Intersection) type);
		case SEMTYPE_difference:
			return extractDifference((SemanticType.Difference) type);
		}
		throw new IllegalArgumentException("invalid type encountered: " + type);
	}

	private SemanticType.Array extractNominal(Type.Nominal type) {
		try {
			Decl.Type decl = resolver.resolveExactly(type.getName(), Decl.Type.class);
			return extractType(decl.getType());
		} catch (NameResolver.ResolutionError e) {
			// FIXME: This is obviously not ideal, but it's a temporary fix for now. In the
			// future, the use of NameResolver will be deprecated.
			throw new RuntimeException(e);
		}
	}

	private Type.Array extractUnion(Type.Union type) {
		Type[] types = type.getAll();
		Type[] elements = new Type[types.length];
		for(int i=0;i!=types.length;++i) {
			// NOTE: following cast is indeed safe as, given Type, must extract Type.Array.
			Type.Array array = (Type.Array) extractType(types[i]);
			if(array == null) {
				elements[i] = null;
			} else {
				elements[i] = array.getElement();
			}
		}
		elements = ArrayUtils.removeAll(elements, null);
		switch(elements.length) {
		case 0:
			return new Type.Array(Type.Void);
		case 1:
			return new Type.Array(elements[0]);
		default:
			return new Type.Array(new Type.Union(elements));
		}
	}

	private SemanticType.Array extractUnion(SemanticType.Union type) {
		SemanticType[] types = type.getAll();
		SemanticType[] elements = new SemanticType[types.length];
		for(int i=0;i!=types.length;++i) {
			SemanticType.Array array = extractType(types[i]);
			if(array == null) {
				elements[i] = null;
			} else {
				elements[i] = array.getElement();
			}
		}
		elements = ArrayUtils.removeAll(elements, null);
		switch(elements.length) {
		case 0:
			return new SemanticType.Array(Type.Void);
		case 1:
			return new SemanticType.Array(elements[0]);
		default:
			return new SemanticType.Array(new SemanticType.Union(elements));
		}
	}

	private SemanticType.Array extractIntersection(SemanticType.Intersection t) {
		SemanticType[] types = t.getAll();
		SemanticType[] elements = new SemanticType[types.length];
		for(int i=0;i!=types.length;++i) {
			SemanticType.Array array = extractType(types[i]);
			elements[i] = array.getElement();
		}
		return new SemanticType.Array(new SemanticType.Intersection(elements));
	}

	private SemanticType.Array extractDifference(SemanticType.Difference type) {
		SemanticType.Array lhs = extractType(type.getLeftHandSide());
		SemanticType.Array rhs = extractType(type.getRightHandSide());
		if (rhs == null) {
			return lhs;
		} else {
			return new SemanticType.Array(new SemanticType.Difference(lhs.getElement(), rhs.getElement()));
		}
	}
}
