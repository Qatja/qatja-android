package se.goransson.qatja.client;


import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

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
public class PublishFragment extends Fragment {

	EditText topic, message;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_publish, container, false);

		topic = (EditText) v.findViewById(R.id.publish_topic);
		message = (EditText) v.findViewById(R.id.publish_message);

		Button btn = (Button) v.findViewById(R.id.publish_button);
		btn.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				MainActivity act = (MainActivity) getActivity();
				act.publish(topic.getText().toString(), message.getText()
						.toString());
			}
		});

		return v;
	}
}
