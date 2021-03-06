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

package testful.runner;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Allows one to retrieve something remotely (e.g., the bytecode of classes)
 *
 * @author matteo
 */
public interface DataFinder extends Remote {

	public String getKey() throws RemoteException;

	/**
	 * Retrieves something remotely (e.g., the bytecode of classes)
	 * @param type the type of the data. It MUST not contain a "#"
	 * @param id the id of the information
	 * @return the payload containing the information
	 */
	public byte[] getData(String type, String id) throws RemoteException;
}
