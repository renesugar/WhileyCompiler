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

import wybs.lang.NameResolver;
import wybs.lang.NameResolver.ResolutionError;
import wybs.util.AbstractCompilationUnit.Identifier;
import wybs.util.AbstractCompilationUnit.Tuple;
import static wyc.lang.WhileyFile.*;

import wyc.lang.WhileyFile.Type;
import wyc.lang.WhileyFile.Type.Atom;
import wyil.type.TypeSystem;
import wyil.type.SubtypeOperator.LifetimeRelation;

/**
 * <p>
 * Responsible for extracting a readable type, such as a readable array or
 * readable record type. This is a conservative approximation of that described
 * in a given type which is safe to use when reading elements from that type.
 * For example, <code>(int[]|bool[])</code> has a readable array type.
 * </p>
 * <p>
 * <b>Readable Arrays</b>. For example, the type <code>(int[])|(bool[])</code>
 * has a readable array type of <code>(int|bool)[]</code>. This is the readable
 * type as, if we were to read an element from either bound, the return type
 * would be in <code>int|bool</code>. However, we cannot use the readable array
 * type for writing as this could be unsafe. For example, if we actually had an
 * array of type <code>int[]</code>, then writing a boolean value is not
 * permitted. Not all types have readable array type and, furthermore, care must
 * be exercised for those that do. For example, <code>(int[])|int</code> does
 * not have a readable array type. Finally, negations play an important role in
 * determining the readable array type. For example,
 * <code>(int|null)[] & !(int[])</code> generates the readable array type
 * <code>null[]</code>.
 * </p>
 *
 * <p>
 * <b>Readable Records</b>. For example, the type <code>{int f}|{bool f}</code>
 * has a readable record type of <code>{int|bool f}</code>. This is the readable
 * type as, if we were to read field <code>f</code> from either bound, the
 * return type would be in <code>int|bool</code>. However, we cannot use the
 * readable record type for writing as this could be unsafe. For example, if we
 * actually had a record of type <code>{int f}</code>, then writing a boolean
 * value is not permitted. Not all types have readable record type and,
 * furthermore, care must be exercised for those that do. For example,
 * <code>{int f}|int</code> does not have a readable record type. Likewise, the
 * readable record type for <code>{int f, int g}|{bool f}</code> is
 * <code>{int|bool f, ...}</code>. Finally, negations play an important role in
 * determining the readable record type. For example,
 * <code>{int|null f} & !{int f}</code> generates the readable record type
 * <code>{null f}</code>.
 * </p>
 *
 * <p>
 * <b>Readable References.</b>For example, the type <code>(&int)|(&bool)</code>
 * has a readable reference type of <code>&(int|bool)</code>. This is the
 * readable type as, if we were to read an element from either bound, the return
 * type would be in <code>int|bool</code>. However, we cannot use the readable
 * reference type for writing as this could be unsafe. For example, if we
 * actually had an reference of type <code>&int</code>, then writing a boolean
 * value is not permitted. Not all types have a readable reference type and,
 * furthermore, care must be exercised for those that do. For example,
 * <code>(&int)|int</code> does not have a readable reference type.
 * </p>
 *
 * @author David J. Pearce
 *
 */
public class ReadableTypeExtractor extends AbstractTypeExtractor<Type> {

	public ReadableTypeExtractor(NameResolver resolver, TypeSystem typeSystem) {
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
		int opcode = lhs.getOpcode();
		if(opcode == rhs.getOpcode()) {
			switch (opcode) {
			case TYPE_record:
				return union((Type.Record) lhs, (Type.Record) rhs);
			case TYPE_array:
				return union((Type.Array) lhs, (Type.Array) rhs);
			case TYPE_reference:
				return union((Type.Reference) lhs, (Type.Reference) rhs);
			}
		}
		return null;
	}

	@Override
	protected Type intersect(Type lhs, Type rhs) {
		int opcode = lhs.getOpcode();
		if(opcode == rhs.getOpcode()) {
			switch (opcode) {
			case TYPE_record:
				return intersect((Type.Record) lhs, (Type.Record) rhs);
			case TYPE_array:
				return intersect((Type.Array) lhs, (Type.Array) rhs);
			case TYPE_reference:
				return intersect((Type.Reference) lhs, (Type.Reference) rhs);
			}
		}
		return null;
	}

	@Override
	protected Type subtract(Type lhs, Type rhs) {
		int opcode = lhs.getOpcode();
		if(opcode == rhs.getOpcode()) {
			switch (opcode) {
			case TYPE_record:
				return subtract((Type.Record) lhs, (Type.Record) rhs);
			case TYPE_array:
				return subtract((Type.Array) lhs, (Type.Array) rhs);
			case TYPE_reference:
				return subtract((Type.Reference) lhs, (Type.Reference) rhs);
			}
		}
		// In this case, there is nothing useful to subtract. For example, {int x} - int
		// is just {int x}.
		return lhs;
	}

	// ============================================================
	// Primitives
	// ============================================================

	// ============================================================
	// Records
	// ============================================================

	protected Type.Record union(Type.Record lhs, Type.Record rhs) {
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
					Type type = unionHelper(lhsField.getType(), rhsField.getType());
					fields.add(new Decl.Variable(new Tuple<>(), lhsFieldName, type));
				}
			}
		}
		//
		boolean isOpenRecord = lhs.isOpen() || rhs.isOpen();
		isOpenRecord |= (lhsFields.size() > fields.size() || rhsFields.size() > fields.size());
		//
		return new Type.Record(isOpenRecord, new Tuple<>(fields));
	}

	// ============================================================
	// Arrays
	// ============================================================

	protected Type.Array union(Type.Array lhs, Type.Array rhs) {
		//
		return new Type.Array(unionHelper(lhs.getElement(), rhs.getElement()));
	}

	protected Type.Array subtract(Type.Array lhs, Type.Array rhs) {
		return new Type.Array(new Type.Difference(lhs.getElement(),rhs.getElement()));
	}

	protected Type.Array intersect(Type.Array lhs, Type.Array rhs) {
		return new Type.Array(intersectionHelper(lhs.getElement(), rhs.getElement()));
	}

	// ============================================================
	// References
	// ============================================================

	protected Type.Reference union(Type.Reference lhs, Type.Reference rhs) {
		//
		return new Type.Reference(intersectionHelper(lhs.getElement(),rhs.getElement()));
	}

	protected Type.Reference subtract(Type.Reference lhs, Type.Reference rhs) {
		return new Type.Reference(new Type.Difference(lhs.getElement(),rhs.getElement()));
	}

	protected Type.Reference intersect(Type.Reference lhs, Type.Reference rhs) {
		return new Type.Reference(intersectionHelper(lhs.getElement(), rhs.getElement()));
	}
}
