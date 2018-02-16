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
import wyc.lang.WhileyFile.Type.Array;
import wyc.lang.WhileyFile.SemanticType;

/**
 * Extract a <code>Type.Array</code> from an arbitrary
 * <code>SemanticType</code>. This requires that the given
 * <code>SemanticType</code> corresponds exactly to an array.
 *
 * @author David J. Pearce
 *
 */
public class TypeArrayExtractor implements SemanticTypeFunction<SemanticType,Type.Array> {

	@Override
	public Array apply(SemanticType t) {
		// SemanticType.asArray goes here.
		return null;
	}

}
