package wyil.type.subtyping;

import wybs.lang.NameResolver;
import wybs.lang.NameResolver.ResolutionError;
import wybs.util.AbstractCompilationUnit.Tuple;
import wyc.lang.WhileyFile.Decl;
import wyc.lang.WhileyFile.Type;
import wyil.type.subtyping.EmptinessTest.LifetimeRelation;
import wyil.type.subtyping.TypeEmptinessTest.State;

import static wyc.lang.WhileyFile.*;

public class SemanticEmptinessTest implements EmptinessTest<SemanticType> {
	private final NameResolver resolver;
	private final EmptinessTest<Type> subtypeOperator;

	public SemanticEmptinessTest(NameResolver resolver, EmptinessTest<Type> subtypeOperator) {
		this.resolver = resolver;
		this.subtypeOperator = subtypeOperator;
	}

	@Override
	public boolean isVoid(SemanticType lhs, State lhsState, SemanticType rhs, State rhsState,
			LifetimeRelation lifetimes) throws ResolutionError {
		if(lhs.getOpcode() == rhs.getOpcode()) {
			switch (lhs.getOpcode()) {
			case SEMTYPE_union:
				return isVoidUnion((SemanticType.Union) lhs, lhsState, rhs, rhsState, lifetimes);
			case SEMTYPE_intersection:
				return isVoidIntersection((SemanticType.Intersection) lhs, lhsState, rhs, rhsState, lifetimes);
			case SEMTYPE_difference:
				return isVoidDifference((SemanticType.Difference) lhs, lhsState, rhs, rhsState, lifetimes);
			case SEMTYPE_array:
				return isVoidArray((SemanticType.Array) lhs, lhsState, (SemanticType.Array) rhs, rhsState, lifetimes);
			case SEMTYPE_record:
				return isVoidRecord((SemanticType.Record) lhs, lhsState, (SemanticType.Record) rhs, rhsState,
						lifetimes);
			case SEMTYPE_reference:
				return isVoidReference((SemanticType.Reference) lhs, lhsState, (SemanticType.Reference) rhs, rhsState,
						lifetimes);
			}
		} else {
			// First, match left-hand side
			switch (lhs.getOpcode()) {
			case SEMTYPE_union:
				return isVoidUnion((SemanticType.Union) lhs, lhsState, rhs, rhsState, lifetimes);
			case SEMTYPE_intersection:
				return isVoidIntersection((SemanticType.Intersection) lhs, lhsState, rhs, rhsState, lifetimes);
			case SEMTYPE_difference:
				return isVoidDifference((SemanticType.Difference) lhs, lhsState, rhs, rhsState, lifetimes);
			}
			// Second, match right-hand side
			switch (rhs.getOpcode()) {
			case SEMTYPE_union:
				return isVoidUnion((SemanticType.Union) rhs, rhsState, lhs, lhsState, lifetimes);
			case SEMTYPE_intersection:
				return isVoidIntersection((SemanticType.Intersection) rhs, rhsState, lhs, lhsState, lifetimes);
			case SEMTYPE_difference:
				return isVoidDifference((SemanticType.Difference) rhs, rhsState, lhs, lhsState, lifetimes);
			}
		}
		// Finally, handle leaf cases
		if (lhs instanceof SemanticType.Leaf) {
			SemanticType.Leaf leaf = (SemanticType.Leaf) lhs;
			return isVoidType(leaf.getType(), lhsState, rhs, rhsState, lifetimes);
		} else {
			// Only remaining possibility
			SemanticType.Leaf leaf = (SemanticType.Leaf) rhs;
			return isVoidType(leaf.getType(), rhsState, lhs, lhsState, lifetimes);
		}
	}

	private boolean isVoidUnion(SemanticType.Union lhs, State lhsState, SemanticType rhs, State rhsState,
			LifetimeRelation lifetimes) throws ResolutionError {
		if (lhsState.sign) {
			for(int i=0;i!=lhs.size();++i) {
				if(!isVoid(lhs.get(i), lhsState, rhs, rhsState, lifetimes)) {
					return false;
				}
			}
			return true;
		} else {
			for(int i=0;i!=lhs.size();++i) {
				if(isVoid(lhs.get(i), lhsState, rhs, rhsState, lifetimes)) {
					return true;
				}
			}
			return false;
		}
	}

	private boolean isVoidIntersection(SemanticType.Intersection lhs, State lhsState, SemanticType rhs, State rhsState,
			LifetimeRelation lifetimes) throws ResolutionError {
		if (lhsState.sign) {
			for(int i=0;i!=lhs.size();++i) {
				if(isVoid(lhs.get(i), lhsState, rhs, rhsState, lifetimes)) {
					return true;
				}
			}
			return false;
		} else {
			for(int i=0;i!=lhs.size();++i) {
				if(isVoid(lhs.get(i), lhsState, rhs, rhsState, lifetimes)) {
					return false;
				}
			}
			return true;
		}
	}

	private boolean isVoidDifference(SemanticType.Difference lhs, State lhsState, SemanticType rhs, State rhsState,
			LifetimeRelation lifetimes) throws ResolutionError {
		if (lhsState.sign) {
			return isVoid(lhs.getLeftHandSide(), lhsState, rhs, rhsState, lifetimes)
					&& isVoid(lhs.getRightHandSide(), lhsState.invert(), rhs, rhsState, lifetimes);
		} else {
			return isVoid(lhs.getLeftHandSide(), lhsState.invert(), rhs, rhsState, lifetimes)
					|| isVoid(lhs.getRightHandSide(), lhsState, rhs, rhsState, lifetimes);
		}
	}

	private boolean isVoidArray(SemanticType.Array lhs, State lhsState, SemanticType.Array rhs, State rhsState, LifetimeRelation lifetimes) throws ResolutionError {
		SemanticType lhsTerm = lhs.getElement();
		SemanticType rhsTerm = rhs.getElement();

		if (lhsState.sign && rhsState.sign) {
			// In this case, we are intersecting two array types, of which at
			// least one is positive. This is void only if there is no
			// intersection of the underlying element types. For example, int[]
			// and bool[] is void, whilst (int|null)[] and int[] is not. Note that void[]
			// always intersects with another array type regardless and, hence, we must
			// check whether the element type of each is void or not.
			return isVoid(lhsTerm, lhsState, rhsTerm, rhsState, lifetimes)
					&& !isVoid(lhsTerm, lhsState, lhsTerm, rhsState, lifetimes)
					&& !isVoid(rhsTerm, lhsState, rhsTerm, rhsState, lifetimes);
		} else if (lhsState.sign || rhsState.sign) {
			// int[] & !bool[] != 0, as int & !bool != 0
			// int[] & !int[] == 0, as int & !int == 0
			// void[] & !int[] == 0, as void && !int == 0
			// int[] & !void[] != 0, as int && !void != 0
			return isVoid(lhsTerm, lhsState, rhsTerm, rhsState, lifetimes);
		} else {
			// In this case, we are intersecting two negative array types. For
			// example, !(int[]) and !(bool[]). This never reduces to void.
			return false;
		}
	}

	private boolean isVoidReference(SemanticType.Reference lhs, State lhsState, SemanticType.Reference rhs, State rhsState, LifetimeRelation lifetimes) throws ResolutionError {
		throw new RuntimeException("implement me");
	}

	private boolean isVoidRecord(SemanticType.Record lhs, State lhsState, SemanticType.Record rhs, State rhsState, LifetimeRelation lifetimes) throws ResolutionError {
		throw new RuntimeException("implement me");
	}

	private boolean isVoidType(Type lhs, State lhsState, SemanticType rhs, State rhsState, LifetimeRelation lifetimes) throws ResolutionError {
		// ASSERT: rhs is not union, intersection or difference.
		if(rhs instanceof SemanticType.Leaf) {
			SemanticType.Leaf t = (SemanticType.Leaf) rhs;
			return isVoidType(lhs, lhsState, t.getType(), rhsState, lifetimes);
		} else if(lhs instanceof Type.Any) {
			return !lhsState.sign;
		} else if(lhs instanceof Type.Union) {
			return isVoidTypeUnion((Type.Union) lhs, lhsState, rhs, rhsState, lifetimes);
		} else if(lhs instanceof Type.Nominal) {
			return isVoidTypeNominal((Type.Nominal) lhs, lhsState, rhs, rhsState, lifetimes);
		} else if(lhs instanceof Type.Array && rhs instanceof SemanticType.Array) {
			return isVoidTypeArray((Type.Array) lhs, lhsState, (SemanticType.Array) rhs, rhsState, lifetimes);
		} else if(lhs instanceof Type.Reference && rhs instanceof SemanticType.Reference) {
			return isVoidTypeReference((Type.Reference) lhs, lhsState, (SemanticType.Reference) rhs, rhsState, lifetimes);
		} else if(lhs instanceof Type.Record && rhs instanceof SemanticType.Record) {
			return isVoidTypeRecord((Type.Record) lhs, lhsState, (SemanticType.Record) rhs, rhsState, lifetimes);
		} else {
			return true;
		}
	}

	private boolean isVoidType(Type lhs, State lhsState, Type rhs, State rhsState, LifetimeRelation lifetimes) throws ResolutionError {
		return subtypeOperator.isVoid(lhs, lhsState, rhs, rhsState, lifetimes);
	}

	private boolean isVoidTypeUnion(Type.Union lhs, State lhsState, SemanticType rhs, State rhsState, LifetimeRelation lifetimes) throws ResolutionError {
		for(int i=0;i!=lhs.size();++i) {
			// FIXME: something is wrong here with regards to signage.
			if(!isVoidType(lhs.get(i),lhsState,rhs,rhsState,lifetimes)) {
				return false;
			}
		}
		return true;
	}

	private boolean isVoidTypeNominal(Type.Nominal lhs, State lhsState, SemanticType rhs, State rhsState, LifetimeRelation lifetimes) throws ResolutionError {
		Decl.Type decl = resolver.resolveExactly(lhs.getName(), Decl.Type.class);
		// FIXME: problem here with respect to maximisation.
		return isVoidType(decl.getType(),lhsState,rhs,rhsState,lifetimes);
	}

	private boolean isVoidTypeArray(Type.Array lhs, State lhsState, SemanticType.Array rhs, State rhsState, LifetimeRelation lifetimes) throws ResolutionError {
		Type lhsTerm = lhs.getElement();
		SemanticType rhsTerm = rhs.getElement();

		if (lhsState.sign && rhsState.sign) {
			// In this case, we are intersecting two array types, of which at
			// least one is positive. This is void only if there is no
			// intersection of the underlying element types. For example, int[]
			// and bool[] is void, whilst (int|null)[] and int[] is not. Note that void[]
			// always intersects with another array type regardless and, hence, we must
			// check whether the element type of each is void or not.
			return isVoidType(lhsTerm, lhsState, rhsTerm, rhsState, lifetimes)
					&& !isVoidType(lhsTerm, lhsState, lhsTerm, rhsState, lifetimes)
					&& !isVoid(rhsTerm, lhsState, rhsTerm, rhsState, lifetimes);
		} else if (lhsState.sign || rhsState.sign) {
			// int[] & !bool[] != 0, as int & !bool != 0
			// int[] & !int[] == 0, as int & !int == 0
			// void[] & !int[] == 0, as void && !int == 0
			// int[] & !void[] != 0, as int && !void != 0
			return isVoidType(lhsTerm, lhsState, rhsTerm, rhsState, lifetimes);
		} else {
			// In this case, we are intersecting two negative array types. For
			// example, !(int[]) and !(bool[]). This never reduces to void.
			return false;
		}
	}

	private boolean isVoidTypeReference(Type.Reference lhs, State lhsState, SemanticType.Reference rhs, State rhsState,
			LifetimeRelation lifetimes) throws ResolutionError {
		String lhsLifetime = extractLifetime(lhs);
		String rhsLifetime = extractLifetime(rhs);
		//
		State lhsTrueTerm = new State(true, lhsState.maximise);
		State rhsTrueTerm = new State(true, rhsState.maximise);
		State lhsFalseTerm = new State(false, lhsState.maximise);
		State rhsFalseTerm = new State(false, rhsState.maximise);
		// Check whether lhs :> rhs (as (!lhs & rhs) == 0)
		boolean elemLhsSubsetRhs = isVoidType(lhs.getElement(), lhsFalseTerm, rhs.getElement(), rhsTrueTerm, lifetimes);
		// Check whether rhs :> lhs (as (!rhs & lhs) == 0)
		boolean elemRhsSubsetLhs = isVoidType(lhs.getElement(), lhsTrueTerm, rhs.getElement(), rhsFalseTerm, lifetimes);
		// Check whether lhs within rhs
		boolean lhsWithinRhs = lifetimes.isWithin(lhsLifetime, rhsLifetime);
		// Check whether lhs within rhs
		boolean rhsWithinLhs = lifetimes.isWithin(rhsLifetime, lhsLifetime);
		// Calculate whether lhs == rhs
		boolean elemEqual = elemLhsSubsetRhs && elemRhsSubsetLhs;
		//
		if (lhsState.sign && rhsState.sign) {
			// (&T1 & &T2) == 0 iff T1 != T2 || !(lhs in rhs || rhs in lhs)
			return !elemEqual || !lhsWithinRhs && !rhsWithinLhs;
		} else if (lhsState.sign) {
			// (!(&T1) & &T2) == 0 iff T1 == T2 && T2 in T1
			return elemEqual && rhsWithinLhs;
		} else if (rhsState.sign) {
			// (T1 & !(&T2)) == 0 iff T1 == T2 && T1 in T2
			return elemEqual && lhsWithinRhs;
		} else {
			// (!(&T1) & !(&T2)) != 0
			return false;
		}
	}

	private String extractLifetime(Type.Reference ref) {
		if (ref.hasLifetime()) {
			return ref.getLifetime().get();
		} else {
			return "*";
		}
	}

	private String extractLifetime(SemanticType.Reference ref) {
		if (ref.hasLifetime()) {
			return ref.getLifetime().get();
		} else {
			return "*";
		}
	}

	private boolean isVoidTypeRecord(Type.Record lhs, State lhsState, SemanticType.Record rhs, State rhsState, LifetimeRelation lifetimes) throws ResolutionError {
		Tuple<Decl.Variable> lhsFields = lhs.getFields();
		Tuple<SemanticType.Field> rhsFields = rhs.getFields();

		if(lhsState.sign || rhsState.sign) {
			// Attempt to match all fields In the positive-positive case this
			// reduces to void if the fields in either of these differ (e.g.
			// {int f} and {int g}), or if there is no intersection between the
			// same field in either (e.g. {int f} and {bool f}).
			int matches = matchRecordFields(lhs, lhsState, rhs, rhsState, lifetimes);
			//
			if (matches == -1) {
				return lhsState.sign == rhsState.sign;
			} else {
				return analyseRecordMatches(matches, lhsState.sign, lhs.isOpen(), lhsFields, rhsState.sign,
						rhs.isOpen(), rhsFields);
			}
		} else {
			// In this case, we are intersecting two negative record types. For
			// example, !({int f}) and !({int g}). This never reduces to void.
			return false;
		}
	}


	protected int matchRecordFields(Type.Record lhs, State lhsState, SemanticType.Record rhs, State rhsState,
			LifetimeRelation lifetimes) throws ResolutionError {
		Tuple<Decl.Variable> lhsFields = lhs.getFields();
		Tuple<SemanticType.Field> rhsFields = rhs.getFields();
		//
		boolean sign = (lhsState.sign == rhsState.sign);
		int matches = 0;
		//
		for (int i = 0; i != lhsFields.size(); ++i) {
			Decl.Variable lhsField = lhsFields.get(i);
			for (int j = 0; j != rhsFields.size(); ++j) {
				SemanticType.Field rhsField = rhsFields.get(j);
				if (!lhsField.getName().equals(rhsField.getName())) {
					continue;
				} else {
					if (sign == isVoidType(lhsField.getType(), lhsState, rhsField.getType(), rhsState, lifetimes)) {
						// For pos-pos case, there is no intersection
						// between these fields and, hence, no intersection
						// overall; for pos-neg case, there is some
						// intersection between these fields which means
						// that some intersections exists overall. For
						// example, consider the case {int f, int|null g} &
						// !{int f, int g}. There is no intersection for
						// field f (i.e. since int & !int = void), whilst
						// there is an intersection for field g (i.e. since
						// int|null & !int = null). Hence, we can conclude
						// that there is an intersection between them with
						// {int f, null g}.
						return -1;
					} else {
						matches = matches + 1;
					}
				}
			}
		}
		return matches;
	}

	protected boolean analyseRecordMatches(int matches, boolean lhsSign, boolean lhsOpen,
			Tuple<Decl.Variable> lhsFields, boolean rhsSign, boolean rhsOpen, Tuple<SemanticType.Field> rhsFields) {
		// NOTE: Don't touch this method unless you know what you are doing. And, trust
		// me, you don't know what you are doing.
		//
		// Perform comparison
		RecordComparison lhsState = compare(matches, lhsOpen, lhsFields, rhsOpen, rhsFields);
		// Exhaustive case analysis
		switch (lhsState) {
		case UNCOMPARABLE:
			// {int x} & {int y} == 0
			// {int x, ...} & {int y} == 0
			// {int x, ...} & {int y, ...} != 0
			// {int x} & !{int y} != 0
			// !{int x} & !{int y} != 0
			return lhsSign && rhsSign && !(lhsOpen && rhsOpen);
		case SMALLER:
			// {int x} & {int x, ...} != 0
			// !{int x} & {int x, ...} != 0
			// !{int x} & !{int x, ...} != 0
			// {int x} & !{int x, ...} == 0
			return lhsSign && !rhsSign && rhsOpen;
		case GREATER:
			// {int x, ...} & {int x} != 0
			// {int x, ...} & !{int x} != 0
			// !{int x, ...} & !{int x} != 0
			// !{int x, ...} & {int x} == 0
			return !lhsSign && rhsSign && lhsOpen;
		case EQUAL:
			// {int x} & {int x} != 0
			// {int x, ...} & {int x, ...} != 0
			// {int x} & !{int x} == 0
			// {int x, ...} & !{int x, ...} == 0
			return lhsSign != rhsSign;
		default:
			throw new RuntimeException(); // dead code
		}
	}

	protected enum RecordComparison {
		EQUAL, UNCOMPARABLE, SMALLER, GREATER
	}

	protected RecordComparison compare(int matches, boolean lhsOpen, Tuple<Decl.Variable> lhsFields, boolean rhsOpen,
			Tuple<SemanticType.Field> rhsFields) {
		int lhsSize = lhsFields.size();
		int rhsSize = rhsFields.size();
		//
		if (matches < lhsSize && matches < rhsSize) {
			return RecordComparison.UNCOMPARABLE;
		} else if (matches < lhsSize) {
			// {int x, int y} != {int x}
			// {int x, int y} << {int x, ...}
			return rhsOpen ? RecordComparison.SMALLER : RecordComparison.UNCOMPARABLE;
		} else if (matches < rhsSize) {
			// {int x} != {int x, int y}
			// {int x, ...} >> {int x, int y}
			return lhsOpen ? RecordComparison.GREATER : RecordComparison.UNCOMPARABLE;
		} else if (lhsOpen != rhsOpen) {
			// {int x} << {int x, ... }
			// {int x, ...} >> {int x }
			return lhsOpen ? RecordComparison.GREATER : RecordComparison.SMALLER;
		} else {
			// {int x,int y} == {int x,int y}
			// {int x, ... } == {int x, ... }
			return RecordComparison.EQUAL;
		}
	}
}
