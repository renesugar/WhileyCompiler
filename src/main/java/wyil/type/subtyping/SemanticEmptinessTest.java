package wyil.type.subtyping;

import wybs.lang.NameResolver;
import wybs.lang.NameResolver.ResolutionError;
import wyc.lang.WhileyFile.Decl;
import wyc.lang.WhileyFile.Type;
import wyil.type.SemanticType;

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
		// TODO: could presumably make this faster with switch statements.
		if (lhs instanceof SemanticType.Union) {
			return isVoidUnion((SemanticType.Union) lhs, lhsState, rhs, rhsState, lifetimes);
		} else if (rhs instanceof SemanticType.Union) {
			return isVoidUnion((SemanticType.Union) rhs, rhsState, lhs, lhsState, lifetimes);
		} else if (lhs instanceof SemanticType.Intersection) {
			return isVoidIntersection((SemanticType.Intersection) lhs, lhsState, rhs, rhsState, lifetimes);
		} else if (rhs instanceof SemanticType.Intersection) {
			return isVoidIntersection((SemanticType.Intersection) rhs, rhsState, lhs, lhsState, lifetimes);
		} else if (lhs instanceof SemanticType.Difference) {
			return isVoidDifference((SemanticType.Difference) lhs, lhsState, rhs, rhsState, lifetimes);
		} else if (rhs instanceof SemanticType.Difference) {
			return isVoidDifference((SemanticType.Difference) rhs, rhsState, lhs, lhsState, lifetimes);
		} else if (lhs instanceof SemanticType.Array && rhs instanceof SemanticType.Array) {
			return isVoidArray((SemanticType.Array) lhs, lhsState, (SemanticType.Array) rhs, rhsState, lifetimes);
		} else if (lhs instanceof SemanticType.Reference && rhs instanceof SemanticType.Reference) {
			return isVoidReference((SemanticType.Reference) lhs, lhsState, (SemanticType.Reference) rhs, rhsState,
					lifetimes);
		} else if (lhs instanceof SemanticType.Record && rhs instanceof SemanticType.Record) {
			return isVoidRecord((SemanticType.Record) lhs, lhsState, (SemanticType.Record) rhs, rhsState, lifetimes);
		} else if (lhs instanceof SemanticType.Leaf) {
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
			return isVoid(lhs.getLeftHandSide(), lhsState, rhs, rhsState, lifetimes)
					|| isVoid(lhs.getRightHandSide(), lhsState, rhs, rhsState, lifetimes);
		} else {
			return isVoid(lhs.getLeftHandSide(), lhsState, rhs, rhsState, lifetimes)
					&& isVoid(lhs.getRightHandSide(), lhsState, rhs, rhsState, lifetimes);
		}
	}

	private boolean isVoidIntersection(SemanticType.Intersection lhs, State lhsState, SemanticType rhs, State rhsState,
			LifetimeRelation lifetimes) throws ResolutionError {
		if (lhsState.sign) {
			return isVoid(lhs.getLeftHandSide(), lhsState, rhs, rhsState, lifetimes)
					&& isVoid(lhs.getRightHandSide(), lhsState, rhs, rhsState, lifetimes);
		} else {
			return isVoid(lhs.getLeftHandSide(), lhsState, rhs, rhsState, lifetimes)
					|| isVoid(lhs.getRightHandSide(), lhsState, rhs, rhsState, lifetimes);
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

	private boolean isVoidTypeReference(Type.Reference lhs, State lhsState, SemanticType.Reference rhs, State rhsState, LifetimeRelation lifetimes) throws ResolutionError {
		throw new RuntimeException("implement me");
	}

	private boolean isVoidTypeRecord(Type.Record lhs, State lhsState, SemanticType.Record rhs, State rhsState, LifetimeRelation lifetimes) throws ResolutionError {
		throw new RuntimeException("implement me");
	}
}
