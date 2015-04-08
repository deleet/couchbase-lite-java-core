package com.couchbase.lite;

import com.couchbase.lite.router.URLConnection;

import java.util.Map;

public interface JsdsCompiler {

    public JsdsCompiler newInstance();

    public void runScript(String source, JsdsContext jsdsContext, Object[] params, JsdsRunnable callback);

}