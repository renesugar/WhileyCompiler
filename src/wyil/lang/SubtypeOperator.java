package wyil.lang;

import static wyil.lang.Type.*;
import wyautl.lang.*;

public class SubtypeOperator implements Relation {
	private final Automata from;
	private final Automata to;
	private final BinaryMatrix subtypes; // from :> to
	private final BinaryMatrix suptypes; // to :> from
	private final BinaryMatrix intersections;
	
	public SubtypeOperator(Automata from, Automata to) {
		this.from = from;
		this.to = to;
		this.subtypes = new BinaryMatrix(from.size(),to.size(),true);
		this.suptypes = new BinaryMatrix(to.size(),from.size(),true);
		this.intersections = new BinaryMatrix(from.size(),to.size(),true);
	}
	
	public final Automata from() {
		return from;
	}
	
	public final Automata to() {
		return to;
	}
	
	public final boolean update(int fromIndex, int toIndex) {
		boolean oldIntersection = intersections.get(fromIndex,toIndex);
		boolean oldSubtype = subtypes.get(fromIndex,toIndex);
		boolean oldSuptype = suptypes.get(toIndex,fromIndex);
		boolean newIntersection = isIntersection(fromIndex,toIndex);
		boolean newSubtype = isSubtype(fromIndex,from,toIndex,to,subtypes,suptypes);
		boolean newSuptype = isSubtype(toIndex,to,fromIndex,from,suptypes,subtypes);		
		intersections.set(fromIndex,toIndex,newIntersection);
		subtypes.set(fromIndex,toIndex,newSubtype);
		suptypes.set(toIndex,fromIndex,newSuptype);
		return newIntersection != oldIntersection || newSubtype != oldSubtype || newSuptype != oldSuptype;
	}
	
	public final boolean isRelated(int fromIndex, int toIndex) {
		return subtypes.get(fromIndex,toIndex);
	}
	
	// check if to is a subtype of from
	public boolean isSubtype(int fromIndex, Automata from, int toIndex,
			Automata to, BinaryMatrix subtypes, BinaryMatrix suptypes) {
		
		Automata.State fromState = from.states[fromIndex];
		Automata.State toState = to.states[toIndex];
		int fromKind = fromState.kind;
		int toKind = toState.kind;
		
		if(fromKind == toKind) {
			switch(fromKind) {
			// === Leaf States First ===
			case K_EXISTENTIAL:
				NameID nid1 = (NameID) fromState.data;
				NameID nid2 = (NameID) toState.data;				
				return nid1.equals(nid2);
			
			// === Homogenous Compound States ===
			case K_SET:
			case K_LIST:
			case K_PROCESS:
			case K_DICTIONARY:
			case K_TUPLE:  {
				// nary nodes
				int[] fromChildren = fromState.children;
				int[] toChildren = toState.children;
				if (fromChildren.length != toChildren.length) {
					return false;
				}
				for (int i = 0; i < fromChildren.length; ++i) {
					if (!subtypes.get(fromChildren[i], toChildren[i])) {
						return false;
					}
				}
				return true;
			}
			case K_RECORD: {
				int[] fromChildren = fromState.children;
				int[] toChildren = toState.children;
				if (fromChildren.length != toChildren.length) {
					return false;
				}				
				String[] fromFields = (String[]) fromState.data;
				String[] toFields = (String[]) toState.data;				
				
				for (int i = 0; i != fromFields.length; ++i) {
					String e1 = fromFields[i];
					String e2 = toFields[i];
					if(!e1.equals(e2)) { return false; }
					int fromChild = fromChildren[i];
					int toChild = toChildren[i];
					if(!subtypes.get(fromChild,toChild)) {
						return false;
					}					
				}									
				return true;	
			}
			case K_NOT: {
				int fromChild = fromState.children[0];
				int toChild = toState.children[0];
				return suptypes.get(toChild,fromChild);
			}
			case K_UNION: {								
				int[] toChildren = toState.children;		
				for(int j : toChildren) {				
					if(!subtypes.get(fromIndex,j)) { return false; }								
				}
				return true;								
			}	
			case K_INTERSECTION: {								
				int[] fromChildren = fromState.children;		
				for(int i : fromChildren) {				
					if(!subtypes.get(i,toIndex)) { return false; }								
				}
				return true;								
			}				
			// === Heterogenous Compound States ===
			case K_FUNCTION:
			case K_HEADLESS:
			case K_METHOD:
				// nary nodes
				int[] fromChildren = fromState.children;
				int[] toChildren = toState.children;
				if(fromChildren.length != toChildren.length){
					return false;
				}
				int start = 0;
				if(fromKind == K_METHOD) {
					// Check (optional) receiver value first (which is contravariant)
					if (!suptypes.get(toChildren[0],fromChildren[0])) {
						return false;
					}
					start++;
				}
				// Check return value first (which is covariant)
				int fromChild = fromChildren[start];
				int toChild = toChildren[start];
				if(!subtypes.get(fromChild,toChild)) {
					return false;
				}
				// Now, check parameters (which are contra-variant)
				for(int i=start+1;i<fromChildren.length;++i) {
					if(!suptypes.get(toChildren[i],fromChildren[i])) {
						return false;
					}
				}
				return true;
			default:
				// other primitive types (e.g. void, any, null, int, etc)
				return true;
			}
		} else if(fromKind == K_ANY || toKind == K_VOID){
			return true;
		} else if(fromKind == K_UNION) {
			int[] fromChildren = fromState.children;		
			for(int i : fromChildren) {				
				if(subtypes.get(i,toIndex)) {
					return true;
				}								
			}
			return false;	
		} else if(toKind == K_UNION) {
			int[] toChildren = toState.children;		
			for(int j : toChildren) {				
				if(!subtypes.get(fromIndex,j)) {
					return false;
				}								
			}
			return true;	
		} else if(fromKind == K_INTERSECTION) {
			int[] fromChildren = fromState.children;		
			for(int i : fromChildren) {				
				if(!subtypes.get(i,toIndex)) {
					return false;
				}								
			}
			return true;	
		} else if(toKind == K_INTERSECTION) {
			int[] toChildren = toState.children;		
			for(int j : toChildren) {				
				if(subtypes.get(fromIndex,j)) {
					return true;
				}								
			}
			return false;	
		} else if(fromKind == K_NOT) {
			int fromChild = fromState.children[0];
			return !suptypes.get(toIndex,fromChild);
		} else if(toKind == K_NOT) {
			// not sure what to do in this case
		}
		
		return false;
	}	
	
	// check if to & from != 0
	public boolean isIntersection(int fromIndex, int toIndex) {
		
		Automata.State fromState = from.states[fromIndex];
		Automata.State toState = to.states[toIndex];
		int fromKind = fromState.kind;
		int toKind = toState.kind;
		
		if(fromKind == toKind) {
			switch(fromKind) {
			// === Leaf States First ===
			case K_EXISTENTIAL:
				NameID nid1 = (NameID) fromState.data;
				NameID nid2 = (NameID) toState.data;				
				return nid1.equals(nid2);			
			// === Homogenous Compound States ===
			case K_SET:
			case K_LIST:
			case K_PROCESS:
			case K_DICTIONARY:
			case K_TUPLE:  {
				// nary nodes
				int[] fromChildren = fromState.children;
				int[] toChildren = toState.children;
				if (fromChildren.length != toChildren.length) {
					return false;
				}
				for (int i = 0; i < fromChildren.length; ++i) {
					if (!intersections.get(fromChildren[i], toChildren[i])) {
						return false;
					}
				}
				return true;
			}
			case K_RECORD: {
				int[] fromChildren = fromState.children;
				int[] toChildren = toState.children;
				if (fromChildren.length != toChildren.length) {
					return false;
				}				
				String[] fromFields = (String[]) fromState.data;
				String[] toFields = (String[]) toState.data;				
				
				for (int i = 0; i != fromFields.length; ++i) {
					String e1 = fromFields[i];
					String e2 = toFields[i];
					if(!e1.equals(e2)) { return false; }
					int fromChild = fromChildren[i];
					int toChild = toChildren[i];
					if(!intersections.get(fromChild,toChild)) {
						return false;
					}					
				}									
				return true;	
			}
			case K_NOT: {
				int fromChild = fromState.children[0];
				int toChild = toState.children[0];
					return intersections.get(fromIndex, toChild)
							&& intersections.get(fromChild, toIndex);
			}
			case K_UNION : {
				int[] toChildren = toState.children;
				for (int j : toChildren) {
					if (intersections.get(fromIndex, j)) {
						return true;
					}
				}
				return false;
			}
			case K_INTERSECTION : {
				int[] fromChildren = fromState.children;
				for (int i : fromChildren) {
					if (!intersections.get(i, toIndex)) {
						return false;
					}
				}
				return true;
			}
			// === Heterogenous Compound States ===
			case K_FUNCTION:
			case K_HEADLESS:
			case K_METHOD:
				// nary nodes
				int[] fromChildren = fromState.children;
				int[] toChildren = toState.children;
				if(fromChildren.length != toChildren.length){
					return false;
				}
				int start = 0;
				if(fromKind == K_METHOD) {
					// Check (optional) receiver value first
					if (!intersections.get(fromChildren[0],toChildren[0])) {
						return false;
					}
					start++;
				}
				// Check return value first 
				int fromChild = fromChildren[start];
				int toChild = toChildren[start];
				if(!intersections.get(fromChild,toChild)) {
					return false;
				}
				// Now, check parameters 
				for(int i=start+1;i<fromChildren.length;++i) {
					if(!intersections.get(toChildren[i],fromChildren[i])) {
						return false;
					}
				}
				return true;
			default:
				// other primitive types (e.g. void, any, null, int, etc)
				return true;
			}
		} else if(fromKind == K_ANY || toKind == K_ANY){
			return true;
		} else if(fromKind == K_VOID || toKind == K_VOID){
			return true;
		} else if(fromKind == K_UNION) {
			int[] fromChildren = fromState.children;		
			for(int i : fromChildren) {				
				if(intersections.get(i,toIndex)) {
					return true;
				}								
			}
			return false;	
		} else if(toKind == K_UNION) {
			int[] toChildren = toState.children;		
			for(int j : toChildren) {				
				if(intersections.get(fromIndex,j)) {
					return true;
				}								
			}
			return false;	
		} else if(fromKind == K_INTERSECTION) {
			int[] fromChildren = fromState.children;
			for (int i : fromChildren) {
				if (!intersections.get(i, toIndex)) {
					return false;
				}
			}
			return true;	
		} else if(toKind == K_INTERSECTION) {
			int[] toChildren = toState.children;
			for (int j : toChildren) {
				if (!intersections.get(fromIndex, j)) {
					return false;
				}
			}
			return true;	
		} else if(fromKind == K_NOT) {
			int fromChild = fromState.children[0];
			return !subtypes.get(fromChild,toIndex);
		} else if(toKind == K_NOT) {
			int toChild = toState.children[0];
			return !suptypes.get(toChild,fromIndex);
		}
		
		return false;
	}
}
