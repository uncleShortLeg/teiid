/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.dqp.internal.process;

import javax.resource.spi.work.Work;
import javax.resource.spi.work.WorkEvent;
import javax.resource.spi.work.WorkListener;

import org.teiid.core.TeiidRuntimeException;
import org.teiid.logging.LogManager;



/**
 * Represents a task that performs work that may take more than one processing pass to complete.
 * During processing the WorkItem may receive events asynchronously through the moreWork method.
 */
public abstract class AbstractWorkItem implements Work, WorkListener {
	
    enum ThreadState {
    	MORE_WORK, WORKING, IDLE, DONE
    }
    
    private ThreadState threadState = ThreadState.MORE_WORK;
    private volatile boolean isProcessing;
    private Thread callingThread;
    
    public AbstractWorkItem(Thread callingThread) {
    	this.callingThread = callingThread;
    }
    
    public void run() {
    	do {
			startProcessing();
			try {
				process();
			} finally {
				if (!endProcessing()) {
					break;
				}
			}
    	} while (!isDoneProcessing());
    }
    
    synchronized ThreadState getThreadState() {
    	return this.threadState;
    }
    
    public boolean isProcessing() {
		return isProcessing;
	}
    
    private synchronized void startProcessing() {
    	isProcessing = true;
    	logTrace("start processing"); //$NON-NLS-1$
		if (this.threadState != ThreadState.MORE_WORK) {
			throw new IllegalStateException("Must be in MORE_WORK"); //$NON-NLS-1$
		}
    	this.threadState = ThreadState.WORKING;
	}
    
    /**
     * @return true if processing should be continued
     */
    final private synchronized boolean endProcessing() {
    	isProcessing = false;
    	logTrace("end processing"); //$NON-NLS-1$
    	switch (this.threadState) {
	    	case WORKING:
	    		if (isDoneProcessing()) {
	    			logTrace("done processing"); //$NON-NLS-1$
	        		this.threadState = ThreadState.DONE;
	        	} else {
		    		this.threadState = ThreadState.IDLE;
		    		return pauseProcessing();
	        	}
	    		break;
	    	case MORE_WORK:
	    		if (isDoneProcessing()) {
	    			logTrace("done processing - ignoring more"); //$NON-NLS-1$
	        		this.threadState = ThreadState.DONE;
	        	} else if (this.callingThread == null) {
        			resumeProcessing();
	        	}
	    		break;
    		default:
    			throw new IllegalStateException("Should not END on " + this.threadState); //$NON-NLS-1$
    	}
    	return this.callingThread != null;
    }
    
    protected boolean isIdle() {
    	return this.threadState == ThreadState.IDLE;
    }
    
    public void moreWork() {
    	moreWork(true);
    }
    
    final protected synchronized void moreWork(boolean ignoreDone) {
    	logTrace("more work"); //$NON-NLS-1$
    	switch (this.threadState) {
	    	case WORKING:
	    		this.threadState = ThreadState.MORE_WORK;
	    		break;
	    	case MORE_WORK:
	    		break;
	    	case IDLE:
	    		this.threadState = ThreadState.MORE_WORK;
        		if (this.callingThread != null) {
        			if (this.callingThread == Thread.currentThread()) {
        				run(); //restart with the calling thread
        			} else {
        				this.notifyAll(); //notify the waiting caller
        			}
        		} else {
        			resumeProcessing();
        		}
	    		break;
			default:
				if (!ignoreDone) {
					throw new IllegalStateException("More work is not valid once DONE"); //$NON-NLS-1$
				}
				LogManager.logDetail(org.teiid.logging.LogConstants.CTX_DQP, new Object[] {this, "ignoring more work, since the work item is done"}); //$NON-NLS-1$
    	}
    }
    
	private void logTrace(String msg) {
		LogManager.logTrace(org.teiid.logging.LogConstants.CTX_DQP, new Object[] {this, msg, this.threadState}); 
	}
    
    protected abstract void process();

	protected boolean pauseProcessing() {
		if (this.callingThread != null && !shouldPause()) {
			return false;
		}
		while (this.callingThread != null && this.getThreadState() == ThreadState.IDLE) {
			try {
				this.wait(); //the lock should already be held
			} catch (InterruptedException e) {
				interrupted(e);
			}
		}
		return this.callingThread != null;
	}
	
	/**
	 * only called for synch processing
	 */
	protected boolean shouldPause() {
		return false;
	}

	/**
	 * only called for synch processing
	 */
	protected void interrupted(InterruptedException e) {
		throw new TeiidRuntimeException(e);
	}
    
    protected abstract void resumeProcessing();
	
    protected abstract boolean isDoneProcessing();
    
    public abstract String toString();
    
    @Override
    public void release() {
    	
    }
    
	@Override
	public void workAccepted(WorkEvent arg0) {
	}

	@Override
	public void workCompleted(WorkEvent arg0) {
	}

	@Override
	public void workRejected(WorkEvent event) {
	}

	@Override
	public void workStarted(WorkEvent arg0) {
	}		
}
