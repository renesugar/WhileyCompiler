package wyil.type.subtyping;

import wybs.lang.NameID;
import wybs.lang.NameResolver.ResolutionError;
import wyc.lang.WhileyFile.Decl;
import wyc.lang.WhileyFile.Type;
import wyil.type.SemanticSubtypeOperator;
import wyil.type.SemanticType;
import wyil.type.SubtypeOperator;
import wyil.type.TypeSystem;

public class SemanticSubtypeOperatorAdaptor implements SemanticSubtypeOperator {
	private final TypeSystem typeSystem;
	private final SubtypeOperator subtypeOperator;

	public SemanticSubtypeOperatorAdaptor(TypeSystem typeSystem,SubtypeOperator subtypeOperator) {
		this.typeSystem = typeSystem;
		this.subtypeOperator = subtypeOperator;
	}

	@Override
	public boolean isSubtype(SemanticType lhs, SemanticType rhs, LifetimeRelation lifetimes) throws ResolutionError {
		return isVoid(lhs,false,rhs,true,lifetimes);
	}

	@Override
	public boolean isVoid(SemanticType type, LifetimeRelation lifetimes) throws ResolutionError {
		return isVoid(type,true,type,true,lifetimes);
	}

	public boolean isVoid(SemanticType lhs, boolean lhsSign, SemanticType rhs, boolean rhsSign, LifetimeRelation lifetimes) throws ResolutionError {
		// TODO: could presumably make this faster with switch statements.
		if(lhs instanceof SemanticType.Union) {
			return isVoidUnion((SemanticType.Union) lhs, lhsSign, rhs, rhsSign, lifetimes);
		} else if(rhs instanceof SemanticType.Union) {
			return isVoidUnion((SemanticType.Union) rhs, rhsSign, lhs, lhsSign, lifetimes);
		} else if(lhs instanceof SemanticType.Intersection) {
			return isVoidIntersection((SemanticType.Intersection) lhs, lhsSign, rhs, rhsSign, lifetimes);
		} else if(rhs instanceof SemanticType.Intersection) {
			return isVoidIntersection((SemanticType.Intersection) rhs, rhsSign, lhs, lhsSign, lifetimes);
		} else if(lhs instanceof SemanticType.Difference) {
			return isVoidDifference((SemanticType.Difference) lhs, lhsSign, rhs, rhsSign, lifetimes);
		} else if(rhs instanceof SemanticType.Difference) {
			return isVoidDifference((SemanticType.Difference) rhs, rhsSign, lhs, lhsSign, lifetimes);
		} else if(lhs instanceof SemanticType.Array && rhs instanceof SemanticType.Array) {
			return isVoidArray((SemanticType.Array) lhs, lhsSign, (SemanticType.Array) rhs, rhsSign, lifetimes);
		} else if(lhs instanceof SemanticType.Reference && rhs instanceof SemanticType.Reference) {
			return isVoidReference((SemanticType.Reference) lhs, lhsSign, (SemanticType.Reference) rhs, rhsSign, lifetimes);
		} else if(lhs instanceof SemanticType.Record && rhs instanceof SemanticType.Record ) {
			return isVoidRecord((SemanticType.Record) lhs, lhsSign, (SemanticType.Record) rhs, rhsSign, lifetimes);
		} else if(lhs instanceof SemanticType.Leaf) {
			SemanticType.Leaf leaf = (SemanticType.Leaf) lhs;
			return isVoidType(leaf.getType(), lhsSign, rhs, rhsSign, lifetimes);
		} else {
			// Only remaining possibility
			SemanticType.Leaf leaf = (SemanticType.Leaf) rhs;
			return isVoidType(leaf.getType(), rhsSign, lhs, lhsSign, lifetimes);
		}
	}

	private boolean isVoidUnion(SemanticType.Union lhs, boolean lhsSign, SemanticType rhs, boolean rhsSign, LifetimeRelation lifetimes) throws ResolutionError {
		if(lhsSign) {
			return isVoid(lhs.getLeftHandSide(),lhsSign,rhs,rhsSign,lifetimes) || isVoid(lhs.getRightHandSide(),lhsSign,rhs,rhsSign,lifetimes);
		} else {
			return isVoid(lhs.getLeftHandSide(),lhsSign,rhs,rhsSign,lifetimes) && isVoid(lhs.getRightHandSide(),lhsSign,rhs,rhsSign,lifetimes);
		}
	}

	private boolean isVoidIntersection(SemanticType.Intersection lhs, boolean lhsSign, SemanticType rhs, boolean rhsSign, LifetimeRelation lifetimes) throws ResolutionError {
		if(lhsSign) {
			return isVoid(lhs.getLeftHandSide(),lhsSign,rhs,rhsSign,lifetimes) && isVoid(lhs.getRightHandSide(),lhsSign,rhs,rhsSign,lifetimes);
		} else {
			return isVoid(lhs.getLeftHandSide(),lhsSign,rhs,rhsSign,lifetimes) || isVoid(lhs.getRightHandSide(),lhsSign,rhs,rhsSign,lifetimes);
		}
	}

	private boolean isVoidDifference(SemanticType.Difference lhs, boolean lhsSign, SemanticType rhs, boolean rhsSign, LifetimeRelation lifetimes) throws ResolutionError {
		if(lhsSign) {
			return isVoid(lhs.getLeftHandSide(),lhsSign,rhs,rhsSign,lifetimes) && isVoid(lhs.getRightHandSide(),!lhsSign,rhs,rhsSign,lifetimes);
		} else {
			return isVoid(lhs.getLeftHandSide(),!lhsSign,rhs,rhsSign,lifetimes) || isVoid(lhs.getRightHandSide(),lhsSign,rhs,rhsSign,lifetimes);
		}
	}

	private boolean isVoidArray(SemanticType.Array lhs, boolean lhsSign, SemanticType.Array rhs, boolean rhsSign, LifetimeRelation lifetimes) throws ResolutionError {

	}

	private boolean isVoidReference(SemanticType.Reference lhs, boolean lhsSign, SemanticType.Reference rhs, boolean rhsSign, LifetimeRelation lifetimes) throws ResolutionError {

	}

	private boolean isVoidRecord(SemanticType.Record lhs, boolean lhsSign, SemanticType.Record rhs, boolean rhsSign, LifetimeRelation lifetimes) throws ResolutionError {

	}

	private boolean isVoidType(Type lhs, boolean lhsSign, SemanticType rhs, boolean rhsSign, LifetimeRelation lifetimes) throws ResolutionError {
		// ASSERT: rhs is not union, intersection or difference.
		if(lhs instanceof Type.Union) {
			return isVoidTypeUnion((Type.Union) lhs, lhsSign, rhs, rhsSign, lifetimes);
		} else if(lhs instanceof Type.Nominal) {
			return isVoidTypeNominal((Type.Nominal) lhs, lhsSign, rhs, rhsSign, lifetimes);
		} else if(lhs instanceof Type.Array && rhs instanceof SemanticType.Array) {
			return isVoidTypeArray((Type.Array) lhs, lhsSign, (SemanticType.Array) rhs, rhsSign, lifetimes);
		} else if(lhs instanceof Type.Reference && rhs instanceof SemanticType.Reference) {
			return isVoidTypeReference((Type.Reference) lhs, lhsSign, (SemanticType.Reference) rhs, rhsSign, lifetimes);
		} else if(lhs instanceof Type.Record && rhs instanceof SemanticType.Record) {
			return isVoidTypeRecord((Type.Record) lhs, lhsSign, (SemanticType.Record) rhs, rhsSign, lifetimes);
		} else {
			return true;
		}
	}

	private boolean isVoidTypeUnion(Type.Union lhs, boolean lhsSign, SemanticType rhs, boolean rhsSign, LifetimeRelation lifetimes) throws ResolutionError {
		for(int i=0;i!=lhs.size();++i) {
			// FIXME: something is wrong here with regards to signage.
			if(!isVoidType(lhs.get(i),lhsSign,rhs,rhsSign,lifetimes)) {
				return false;
			}
		}
		return true;
	}

	private boolean isVoidTypeNominal(Type.Nominal lhs, boolean lhsSign, SemanticType rhs, boolean rhsSign, LifetimeRelation lifetimes) throws ResolutionError {
		Decl.Type decl = typeSystem.resolveExactly(lhs.getName(), Decl.Type.class);
		// FIXME: problem here with respect to maximisation.
		return isVoidType(decl.getType(),lhsSign,rhs,rhsSign,lifetimes);
	}

	private boolean isVoidTypeArray(Type.Array lhs, boolean lhsSign, SemanticType.Array rhs, boolean rhsSign, LifetimeRelation lifetimes) throws ResolutionError {

	}

	private boolean isVoidTypeReference(Type.Reference lhs, boolean lhsSign, SemanticType.Reference rhs, boolean rhsSign, LifetimeRelation lifetimes) throws ResolutionError {

	}

	private boolean isVoidTypeRecord(Type.Record lhs, boolean lhsSign, SemanticType.Record rhs, boolean rhsSign, LifetimeRelation lifetimes) throws ResolutionError {

	}


	@Override
	public boolean isContractive(NameID nid, Type type) throws ResolutionError {
		return subtypeOperator.isContractive(nid, type);
	}
}
