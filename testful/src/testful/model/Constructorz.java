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

package testful.model;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

import testful.model.MethodInformation.Kind;
import testful.model.MethodInformation.ParameterInformation;
import testful.model.xml.XmlConstructor;
import testful.model.xml.XmlParameter;

public class Constructorz implements Serializable, Comparable<Constructorz> {

	private static final long serialVersionUID = 1874825146634233768L;

	private final int id;
	private final Clazz clazz;
	private final Clazz[] params;
	private final MethodInformation info;

	/** The maximum execution time (in milliseconds) */
	private final int maxExecutionTime;

	Constructorz(int id, Clazz clazz, Clazz[] params, XmlConstructor xml) {
		this.id = id;
		this.clazz = clazz;
		this.params = params;

		maxExecutionTime = xml.getMaxExecTime();

		List<XmlParameter> paramsXml = xml.getParameter();
		ParameterInformation[] paramsInfo = new ParameterInformation[paramsXml.size()];
		for(int i = 0; i < paramsXml.size(); i++) {
			XmlParameter p = paramsXml.get(i);
			paramsInfo[i] = new ParameterInformation(i);
			paramsInfo[i].setMutated(p.isMutated());
			paramsInfo[i].setCaptured(p.isCaptured());
			paramsInfo[i].setCapturedByReturn(p.isExposedByReturn());
		}

		for(int i = 0; i < paramsXml.size(); i++)
			for(int exch : paramsXml.get(i).getExchangeState())
				paramsInfo[i].addCaptureStateOf(paramsInfo[exch]);

		info = new MethodInformation(Kind.CONSTRUCTOR, true, paramsInfo);
	}

	/**
	 * Returns the identification of the constructorz
	 * @return the identification of the constructorz
	 */
	public int getId() {
		return id;
	}

	public Clazz getClazz() {
		return clazz;
	}

	public MethodInformation getMethodInformation() {
		return info;
	}

	public String getShortConstructorName() {
		return "new " + clazz.getClassName();
	}

	public Clazz[] getParameterTypes() {
		return params;
	}

	/**
	 * Returns the maximum execution time (in milliseconds)
	 * @return the maximum execution time (in milliseconds)
	 */
	public int getMaxExecutionTime() {
		return maxExecutionTime;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(clazz.getClassName()).append("(");

		boolean first = true;
		for(Clazz p : params) {
			if(first) first = false;
			else sb.append(", ");

			sb.append(p.getClassName());
		}

		return sb.append(")").toString();
	}

	@Override
	public int hashCode() {
		return (31 + clazz.hashCode()) * 31 + Arrays.hashCode(params);
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) return true;
		if(obj == null) return false;
		if(!(obj instanceof Constructorz)) return false;

		Constructorz other = (Constructorz) obj;
		return clazz.equals(other.clazz) && Arrays.equals(params, other.params);
	}

	/* (non-Javadoc)
	 * @see java.lang.Comparable#compareTo(java.lang.Object)
	 */
	@Override
	public int compareTo(Constructorz o) {

		final int compareClazz = clazz.compareTo(o.clazz);
		if(compareClazz != 0) return compareClazz;

		if(params.length != o.params.length) return params.length - o.params.length;

		for (int i = 0; i < params.length; i++) {
			final int compareParam = params[i].compareTo(o.params[i]);
			if(compareParam != 0) return compareParam;
		}

		return 0;
	}
}
