package com.couchbase.lite.auth;

import java.net.URL;
import java.util.Map;

public class Authorizer extends AuthenticatorImpl {

    public boolean usesCookieBasedLogin() {
        return false;
    }

    public Map<String, String> loginParametersForSite(URL site) {
        return null;
    }

    public String loginPathForSite(URL site) {
        return null;
    }

}