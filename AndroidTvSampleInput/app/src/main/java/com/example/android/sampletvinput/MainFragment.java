/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.example.android.sampletvinput;

import android.app.Fragment;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import com.example.android.sampletvinput.rich.RichTvInputSetupActivity;

/**
 * Fragment that shows a web page for Sample TV Input introduction.
 * This Fragment will be launched when click the Sample TV App tile in Home Page.
 *
 * If you want to launch the setup activity, please go to:
 * Settings -> Live TV -> Live TV Source -> Amazon Sample TV Input
 *
 * And {@link RichTvInputSetupActivity} will be launched. Please modify RichTvInputSetupActivity
 * if you want customize your setup activity page
 */
public class MainFragment extends Fragment {

    public static final String AMAZON_LIVE_TV_DEV_INTEGRATION_URL = "https://developer.amazon.com/docs/fire-tv/live-tv-integration.html";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.main_fragment, null);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Toast toast=Toast.makeText(getActivity(),"Amazon Sample Tv App launched",Toast.LENGTH_SHORT);
        toast.show();

        //Load the introduction to Amazon Live TV integration Page
        WebView webView = (WebView) getView();
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl(AMAZON_LIVE_TV_DEV_INTEGRATION_URL);
    }
}
