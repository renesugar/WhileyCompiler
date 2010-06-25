// This file is part of the Whiley-to-Java Compiler (wyjc).
//
// The Whiley-to-Java Compiler is free software; you can redistribute 
// it and/or modify it under the terms of the GNU General Public 
// License as published by the Free Software Foundation; either 
// version 3 of the License, or (at your option) any later version.
//
// The Whiley-to-Java Compiler is distributed in the hope that it 
// will be useful, but WITHOUT ANY WARRANTY; without even the 
// implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR 
// PURPOSE.  See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public 
// License along with the Whiley-to-Java Compiler. If not, see 
// <http://www.gnu.org/licenses/>
//
// Copyright 2010, David James Pearce. 

package wyjc.jvm.rt;

import java.util.*;
import java.math.*;

public final class WhileyList extends ArrayList {
	public WhileyList() {
		super();
	}
	
	public WhileyList(Collection c) {
		super(c);
	}
	
	public boolean equals(WhileyList ws) {
		// FIXME: optimisation opportunity here
		return super.equals(ws);
	}
	
	public boolean notEquals(WhileyList ws) {
		return !super.equals(ws);
	}
	
	public WhileyList clone() {
		return new WhileyList(this);
	}
	
	public Object get(BigInteger index) {	
		int idx = index.intValue();
		return get(idx); 		
	}
	
	public WhileyList sublist(BigInteger start, BigInteger end) {
		int st = start.intValue();
		int en = end.intValue();
		WhileyList r = new WhileyList();
		for(int i=st;i!=en;++i) {
			r.add(get(i));
		}
		return r;
	}
		
	public void set(BigInteger index, Object val) {	
		int idx = index.intValue();
		set(idx,val); 		
	}
	
	public static WhileyList range(BigInteger start, BigInteger end) {
		WhileyList ret = new WhileyList();
		
		BigInteger dir = BigInteger.valueOf(end.compareTo(start));
		
		while(!start.equals(end)) {
			ret.add(start);
			start = start.add(dir);
		}
		
		return ret;
	}	
	
	/**
	 * The following method is used by the main launcher to convert from Java's
	 * main(String[] args) into whiley's main([string] args) format.
	 * 
	 * @param args
	 * @return
	 */
	public static WhileyList fromStringList(String[] args) {
		WhileyList r = new WhileyList();
		for(String s : args) {
			r.add(fromString(s));
		}
		return r;
	}
	
	public static WhileyList fromString(String s) {
		WhileyList r = new WhileyList();
		for(int i=0;i!=s.length();++i) {
			int c = s.charAt(i);
			r.add(BigInteger.valueOf(c));
		}
		return r;
	}
	
	public static void println(WhileyList list) {
		for(Object o : list) {
			if(o instanceof BigInteger) {
				BigInteger bi = (BigInteger) o;
				System.out.print((char)bi.intValue());
			}
		}
		System.out.println("");
	}
}
