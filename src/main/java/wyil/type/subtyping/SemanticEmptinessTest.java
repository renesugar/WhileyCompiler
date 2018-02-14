package wyil.type.subtyping;

import wybs.lang.NameResolver;
import wybs.lang.NameResolver.ResolutionError;
import wybs.util.AbstractCompilationUnit.Tuple;
import wyc.lang.WhileyFile.Decl;
import wyc.lang.WhileyFile.SemanticType;
import wyc.lang.WhileyFile.Type;
import wyil.type.subtyping.EmptinessTest.LifetimeRelation;
import wyil.type.subtyping.TypeEmptinessTest.State;

import static wyc.lang.WhileyFile.*;

import java.util.ArrayList;
import java.util.List;

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

	}

	public ArrayList<Term> expand(SemanticType... worklist) {
		ArrayList<Term> done = new ArrayList<>();
		for(int i=0;i!=worklist.length;++i) {
			expand(worklist[i], true, done);
		}
		return done;
	}

	public void expand(SemanticType type, boolean sign, List<Term> expanded) {
		switch (type.getOpcode()) {
		case SEMTYPE_leaf:
			expand((SemanticType.Leaf) type, sign, expanded);
			break;
		case SEMTYPE_union:
			expand((SemanticType.Union) type, sign, expanded);
			break;
		case SEMTYPE_intersection:
			expand((SemanticType.Intersection) type, sign, expanded);
			break;
		case SEMTYPE_difference:
			expand((SemanticType.Difference) type, sign, expanded);
			break;
		case SEMTYPE_array:
			expand((SemanticType.Array) type, sign, expanded);
			break;
		case SEMTYPE_record:
			expand((SemanticType.Record) type, sign, expanded);
			break;
		case SEMTYPE_reference:
			expand((SemanticType.Reference) type, sign, expanded);
			break;
		default:
			throw new IllegalArgumentException("invalid semantic type: " + type);
		}
	}

	public void expand(SemanticType.Leaf type, boolean sign, List<Term> expanded) {
		State state = sign ? EmptinessTest.PositiveMax : EmptinessTest.NegativeMax;
		expanded.add(new Term(type.getType(), state));
	}

	public void expand(SemanticType.Union type, boolean sign, List<Term> expanded) {
		throw new IllegalArgumentException("Implement difficult case");
	}

	public void expand(SemanticType.Intersection type, boolean sign, List<Term> expanded) {
		if(sign) {
			for(int i=0;i!=type.size();++i) {
				expand(type.get(i),sign,expanded);
			}
		} else {
			throw new IllegalArgumentException("Implement difficult case");
		}
	}

	public void expand(SemanticType.Difference type, boolean sign, List<Term> expanded) {
		expand(type.getLeftHandSide(),sign,expanded);
		expand(type.getRightHandSide(),!sign,expanded);
	}

	private static final class Term {
		public final Type type;
		public final State state;

		public Term(Type type, State state) {
			this.type = type;
			this.state = state;
		}
	}
}
