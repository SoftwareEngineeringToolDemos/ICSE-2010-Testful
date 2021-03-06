/*
 * TestFul - http://code.google.com/p/testful/
 * Copyright (C) 2010  Matteo Miraz
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package testful.coverage;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.BitSet;

public abstract class BitSetCoverage implements CoverageInformation {
	private static final long serialVersionUID = 3484091845044514922L;

	/** This field is final (it is not only because the externalizable interface) */
	protected BitSet coverage;

	protected BitSetCoverage() {
		coverage = new BitSet();
	}

	protected BitSetCoverage(BitSet coverage) {
		this.coverage = coverage;
	}

	@Override
	public float getQuality() {
		return coverage.cardinality();
	}

	@Override
	public boolean contains(CoverageInformation other) {
		if(!other.getKey().equals(getKey())) return false;

		BitSet bs = ((BitSetCoverage)other).coverage;

		if(bs.cardinality() > coverage.cardinality()) return false;

		for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i+1))
			if(!coverage.get(i)) return false;

		return true;
	}

	@Override
	public void merge(CoverageInformation other) {
		if(other.getKey().equals(getKey()))
			coverage.or(((BitSetCoverage) other).coverage);
	}

	public BitSet getCoverage() {
		return (BitSet) coverage.clone();
	}

	@Override
	public abstract BitSetCoverage clone() throws CloneNotSupportedException;

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();

		boolean first = true;
		for (int i = coverage.nextSetBit(0); i >= 0; i = coverage.nextSetBit(i+1)) {
			if(first) first = false;
			else sb.append("\n");

			sb.append(i);
		}

		return sb.toString();
	}

	/* (non-Javadoc)
	 * @see java.io.Externalizable#writeExternal(java.io.ObjectOutput)
	 */
	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		out.writeObject(coverage);
	}

	/* (non-Javadoc)
	 * @see java.io.Externalizable#readExternal(java.io.ObjectInput)
	 */
	@Override
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		coverage = (BitSet) in.readObject();
	}
}
