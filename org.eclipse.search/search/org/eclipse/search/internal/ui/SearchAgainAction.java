/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.search.internal.ui;

import org.eclipse.jface.action.Action;

class SearchAgainAction extends Action {

	public SearchAgainAction() {
		super(SearchMessages.SearchResultView_searchAgain_text); 
		setToolTipText(SearchMessages.SearchResultView_searchAgain_tooltip); 
	}
	
	public void run() {
		Search selected= SearchManager.getDefault().getCurrentSearch();
		if (selected != null)
			selected.searchAgain();
	}
}
