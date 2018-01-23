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

import wyc.lang.WhileyFile.Type;

/**
 * Type refinement is the process of selecting a subtype from a given type. For
 * example, given the type <code>int|null</code> we could select either
 * <code>int</code> or <code>null</code>, each of which is a refinement of the
 * original.
 *
 * @author David J. Pearce
 *
 */
public interface TypeRefinement {
	/**
	 * Apply a refinement to a given type, producing a potentially updated type.
	 * Note that, if the original type is not refined at all, then it is required
	 * that this function return the original type back (i.e. rather than e.g.
	 * creating an identical clone of it).
	 *
	 * @param concrete
	 *            The concrete type being refined. This cannot be null.
	 * @param refinement
	 *            The refinement being used to "select" into the concrete type. This
	 *            cannot be null.
	 * @return Returns <code>concrete</code> if no refinement possible or a subtype
	 *         of concrete. In particular, <code>Type.Void</code> returned when
	 *         nothing is left.
	 */
	public Type refine(Type concrete, Type refinement);
}
