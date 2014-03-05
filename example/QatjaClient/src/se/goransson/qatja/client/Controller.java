package se.goransson.qatja.client;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.os.Bundle;

/**
 * Copyright 2014 Andreas Goransson
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 * 
 * @author andreas
 * 
 */
public class Controller {

	private Activity mActivity;
	private FragmentManager mFragmentManager;

	private ConnectionFragment mConnectionFragment;
	private SubscribeFragment mSubscribeFragment;
	private PublishFragment mPublishFragment;

	public Controller(Activity activity) {
		this.mActivity = activity;

		mFragmentManager = mActivity.getFragmentManager();
	}

	public void showConnectionFragment() {
		if (mConnectionFragment == null) {
			mConnectionFragment = new ConnectionFragment();

			Bundle args = new Bundle();
			// Add arguments
			mConnectionFragment.setArguments(args);
		}

		showFragment(mConnectionFragment, "connection", false);
	}

	public void showSubscribeFragment() {
		if (mSubscribeFragment == null) {
			mSubscribeFragment = new SubscribeFragment();

			Bundle args = new Bundle();
			// Add arguments
			mSubscribeFragment.setArguments(args);
		}

		showFragment(mSubscribeFragment, "subscribe");
	}

	public void showPublishFragment() {
		if (mPublishFragment == null) {
			mPublishFragment = new PublishFragment();

			Bundle args = new Bundle();
			// Add arguments
			mPublishFragment.setArguments(args);
		}

		showFragment(mPublishFragment, "subscribe");
	}

	private void showFragment(Fragment frag, String tag) {
		showFragment(frag, tag, true);
	}

	private void showFragment(Fragment frag, String tag, boolean backstack) {
		if (backstack)
			mFragmentManager.beginTransaction()
					.replace(R.id.container, frag, tag).addToBackStack(tag)
					.commitAllowingStateLoss();
		else
			mFragmentManager.beginTransaction()
					.replace(R.id.container, frag, tag)
					.commitAllowingStateLoss();
	}

	public void appendMessage(String text) {
		if (mSubscribeFragment != null) {
			mSubscribeFragment.appendMessage(text);
		}
	}
}
