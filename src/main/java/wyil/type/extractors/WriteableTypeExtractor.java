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
import wybs.util.AbstractCompilationUnit.Identifier;
import wybs.util.AbstractCompilationUnit.Tuple;
import static wyc.lang.WhileyFile.*;

import wyc.lang.WhileyFile.Decl;
import wyc.lang.WhileyFile.Type;
import wyc.lang.WhileyFile.Type.Atom;
import wyil.type.TypeSystem;

/**
 * <p>
 * Responsible for extracting a writeable type, such as a writeable array or
 * writeable record type. This is a conservative approximation of that described
 * in a given type which is safe to use when writing elements from that type.
 * For example, <code>(int[]|any[])</code> has a writeable array type.
 * </p>
 * <p>
 * <b>Writeable Arrays.</b> For example, the type <code>(any[])|(bool[])</code>
 * has a writeable array type of <code>bool[]</code>. This is the writeable type
 * as, if we were to write an element of type <code>bool</code> this is accepted
 * by either bound. However, we cannot use the writeable array type for reading
 * as this could be unsafe. For example, if we actually had an array of type say
 * <code>int[]</code>, then reading a boolean value is not permitted. Not all
 * types have a writeable array type and, furthermore, care must be exercised
 * for those that do. For example, <code>(int[])|int</code> does not have a
 * writeable array type. Finally, negations play an important role in
 * determining the writeable array type. For example,
 * <code>(int|null)[] & !(int[])</code> generates the writeable array type
 * <code>null[]</code>.
 * </p>
 * <p>
 * <b>Writeable Records.</b> For example, the type <code>{int f}|{any f}</code>
 * has a writeable record type of <code>{int f}</code>. This is the writeable
 * type as, if we were to write field <code>f</code> with an <code>int</code>
 * this is safe for either bound. However, we cannot use the writeable record
 * types for reading as this could be unsafe. For example, if we actually had a
 * record of type <code>{bool f}</code>, then reading a int value is not
 * permitted. Not all types have writeable record type and, furthermore, care
 * must be exercised for those that do. For example, <code>{int f}|int</code>
 * does not have a writeable record type. Likewise, the writeable record type
 * for <code>{T1 f, T2 g}|{T3 f}</code> is <code>{T1&T3 f, ...}</code>. Finally,
 * negations play an important role in determining the writeable record type.
 * For example, <code>{int|null f} & !{int f}</code> generates the writeable
 * record type <code>{null f}</code>.
 * </p>
 * <p>
 * <b>Writeable References.</b> For example, the type <code>(&int)|(&any)</code>
 * has a writeable reference type of <code>&int</code>. This is the writeable
 * type as, if we were to write an <code>int</code> to either bound this would
 * be safe. However, we cannot use the readable reference type for reading as
 * this could be unsafe. For example, if we actually had an reference of type
 * <code>&bool</code>, then reading a integer value is not permitted. Not all
 * types have a writeable reference type and, furthermore, care must be
 * exercised for those that do. For example, <code>(&int)|int</code> does not
 * have a writeable reference type.
 * </p>
 *
 * @author David J. Pearce
 *
 */
public class WriteableTypeExtractor extends AbstractTypeExtractor<Type> {

	public WriteableTypeExtractor(NameResolver resolver, TypeSystem typeSystem) {
		super(resolver, typeSystem);
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
					Type type = intersectionHelper(lhsField.getType(), rhsField.getType());
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
		// int[] | bool[] => 0
		// any[] | bool[] => bool[]
		return new Type.Array(intersectionHelper(lhs.getElement(),rhs.getElement()));
	}

	protected Type.Array subtract(Type.Array lhs, Type.Array rhs) {
		return new Type.Array(new Type.Difference(lhs.getElement(),rhs.getElement()));
	}

	protected Type.Array intersect(Type.Array lhs, Type.Array rhs) {
		// {any x, int y}[] & {int x, any y}[] => {int x, int y}[]
		return new Type.Array(intersectionHelper(lhs.getElement(),rhs.getElement()));
	}

	// ============================================================
	// References
	// ============================================================

	protected Type.Reference union(Type.Reference lhs, Type.Reference rhs) {
		return new Type.Reference(intersectionHelper(lhs.getElement(),rhs.getElement()));
	}

	protected Type.Reference subtract(Type.Reference lhs, Type.Reference rhs) {
		return new Type.Reference(new Type.Difference(lhs.getElement(),rhs.getElement()));
	}

	protected Type.Reference intersect(Type.Reference lhs, Type.Reference rhs) {
		return new Type.Reference(intersectionHelper(lhs.getElement(), rhs.getElement()));
	}
}
