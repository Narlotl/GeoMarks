package narlotl.geomarks;

import android.util.Log;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class RecoveryPageWebViewClient extends WebViewClient {
    private final FormSubmitListener listener;
    private final CrashHandler handler;
    public RecoveryPageWebViewClient(FormSubmitListener listener, CrashHandler handler) {
        super();
        this.listener = listener;
        this.handler = handler;
    }

    public void onPageFinished(WebView view, String url) {
        this.listener.onSubmit(url);
    }

    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        Log.e("onRenderProcessGone", detail.toString());
        handler.onCrash();
        return true;
    }

    public interface FormSubmitListener {
        void onSubmit(String url);
    }

    public interface CrashHandler {
        void onCrash();
    }
}
