// This file is part of the Wyone automated theorem prover.
//
// Wyone is free software; you can redistribute it and/or modify 
// it under the terms of the GNU General Public License as published 
// by the Free Software Foundation; either version 3 of the License, 
// or (at your option) any later version.
//
// Wyone is distributed in the hope that it will be useful, but 
// WITHOUT ANY WARRANTY; without even the implied warranty of 
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See 
// the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public 
// License along with Wyone. If not, see <http://www.gnu.org/licenses/>
//
// Copyright 2010, David James Pearce. 

package wyone.testing;

import static org.junit.Assert.assertTrue;
import static wyone.Main.*;

import org.junit.Test;

/**
 * The purpose of this set of tests is to check correct functioning of large
 * integers. Since wone uses bigintegers internally, the real purpose here is
 * to test parsing rather than anything else.
 * 
 * @author djp
 */
public class BigArithmetic {

	private long[] longints = { -9223372036854775808L, 9223372036854775807L,
			0L, -8474111525016252726L, -2802509675177611673L,
			1607712180973772798L, -8175703034471262288L, 1715533125815316082L,
			-4007040503384893473L, -4646436199239366170L,
			-1835285783746353456L, -3249333678376091286L, 3107531512359685217L,
			-3963076365655211760L, 2007024329857161031L, 2639499006181021410L,
			-3381217702926963425L, -8992687860472835306L,
			-3441337178776827965L, -4503371484864546944L,
			-4402067821670992929L, 5583375864008976967L, 8982148808328176835L,
			7544426274948227035L, -735499767541206663L, 8312171174422786086L,
			-1025995632691294438L, -4041172618373657855L, 2604357335606303837L,
			-5528349858760917015L, -6821514123700546960L, 7619647655223430385L,
			-755560563609243894L, -4758466354141042438L, 4076928450153688001L,
			-2848136005520130782L, 2155118313403799192L, 4981590870436413818L,
			3884495987445964764L, -6084055529889091333L, 2521122485976133935L,
			4694721020229732537L, 5669633984350069220L, 733965901337282136L,
			286278655667215502L, -1366473358678910529L, -6062233179472901677L,
			5298063506905415180L, 1672952474743788677L, -7443702531182826041L,
			4520458605278042866L, -5630558982398417319L, 1060646058847101307L,
			-2775719620557410626L, -5697714609498219933L, 2415298923149137273L,
			4257901075852678889L, -3365062555935581752L, -4781829582885210729L,
			4123432570455176217L, -3263005103053315620L, 4394888382538330556L,
			1416258954819729624L, -468699612849203239L, 4117715197152225143L,
			-4998831118813085277L, 4215298985320158657L, 4399936477534825933L,
			67085626496217967L, 4679373232859868988L, 6269342150130350528L,
			5222238830654682367L, -6306972236943690099L, 1233597110612811131L,
			1582651553963914395L, -4945437140295825174L, -2264219446589663952L,
			-8892535783919198458L, 8484003236402917096L, -8353065757650486272L,
			-9095764428467419704L, -535791311076607817L, -6775807840604508013L,
			6522886929290707494L, 3719146870172155740L, -4507410930604624867L,
			-7585343023116175524L, 6023771125898767155L, 3424461045895641093L,
			-9192172859321384454L, -3829406873523577514L, 1331105630402899316L,
			8565498201654489969L, 7914426373456391366L, -5536169678441017494L,
			7605335407271100459L, 5356553254435692897L, 3097364863269722923L,
			-8436920046247357486L, -4544460468426832373L,
			-5849106582649500630L, 8558387326383465095L, 8044687335821484190L,
			-4269171665246844994L, 5937769633364973368L, -5754822341798975123L,
			-8555635372279840206L, 8186186830549220959L, 3604856793141398919L,
			7644688390931438116L, -6260239778464198202L, 3023819044406668800L,
			-640439388596748248L, 8437628311836910886L, -8849732842193929243L,
			7163544463059832870L, 2459352533166699714L, -6208637807734107230L,
			8563001930249721582L, -6416814704093016504L, -4770064908600990706L,
			8197703677035899387L, 7982424615169444246L, 1422853253172644947L,
			-8891248387629316006L, -9084854675748955576L,
			-6771383839663423132L, 5774683061079147305L, 1776177329737956359L,
			9000580590729971410L, 5625803575360911668L, -2830247668011799779L,
			3176731568510380044L, 4110754080696472728L, -3126340499223670859L,
			-1601443845339728264L, 4518068493012206192L, 4040589502210674126L,
			6448507689855233515L, -3208384125000377352L, -2067382681142174509L,
			8495626492852294745L, -20382102914450003L, 7318415682015162700L,
			6200196247454237414L, 1463193855810708565L, -2543216584091373528L,
			4859602343433868697L, -8447249439458580242L, -7955057378293946737L,
			-4963884309219486630L, 6763196216023100408L, -4530563054664160699L,
			2925199451336807180L, -3393679976969978472L, -8958611557713566219L,
			1870910073962371033L, -5733555329076665472L, -7022578498531886584L,
			-622929851682709999L, -6903457915208264020L, -3234233115449240104L,
			5146583287993358632L, 4263918787918201410L, -5383954170512404910L,
			-5057455742513414479L, -3031017281353547195L,
			-3293083776199356818L, -2888946549088508042L, 6788998110796950494L,
			561722146872761093L, -8558854007372146957L, 5300531610268015189L,
			-8605764093122357531L, 8179650437737823710L, -849518012827557086L,
			3318594697206435137L, 2462881115892639619L, 156065011692191900L,
			3944810127404029887L, 6791272665865503519L, -1409532294091717084L,
			3659734204180875999L, -3233654375766969981L, 5580112134905469417L,
			-2540103023485829525L, 8582979559907966106L, 3868466788532359066L,
			1098150199043479854L, 6186210970279162288L, -2922591374819236714L,
			-1488275446489215450L, -4475212165261963123L, 3993492704866474365L,
			-731050741410265364L, -620326925403311942L, 6869231689558804029L,
			4971133295855327761L, 2529774645785146255L, -8594600286115603233L };
	
	@Test public void Unsat_1() { 
		
		for(long x : longints) {
			if(x == Long.MIN_VALUE) {
				// saftey check ...
				x = x + 1;
			}
			String a = Long.toString(x);
			String b = Long.toString(x-1);			
			assertTrue(checkUnsat("int x; x > " + a + " && x <= " + b));
		}
	}

	@Test
	public void Unsat_2() {

		for (long x : longints) {
			if (x == Long.MIN_VALUE) {
				// saftey check ...
				x = x + 1;
			}
			String a = Long.toString(x);
			String b = Long.toString(x - 1);
			assertTrue(checkUnsat("int x; x > " + b + " && x+1 <= " + a));
		}
	}

	@Test
	public void Unsat_3() {

		for (long x : longints) {
			if (x == Long.MIN_VALUE) {
				// saftey check ...
				x = x + 1;
			}
			String a = Long.toString(x);						
			assertTrue(checkUnsat("int x, y; x < y && y < " + a + " && " + a + " < x"));
		}
	}

	@Test
	public void Unsat_4() {

		for (long x : longints) {
			if (x == Long.MIN_VALUE) {
				// saftey check ...
				x = x + 1;
			}
			String a = Long.toString(x);
			String b = Long.toString(x - 1);
			assertTrue(checkUnsat("int x,y; x < y && y <= " + a + "&&" + a + " <= x"));
		}
	}
	
}
