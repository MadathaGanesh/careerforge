package com.careerforge.security;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CsrfTest {
  @Test void csrfBoundToSessionId(){
    var key=new SecretKeySpec(new byte[32],"HmacSHA256");
    String a=AuthSecurity.CookieAuth.csrfFor("session-a",key);
    assertNotEquals(a,AuthSecurity.CookieAuth.csrfFor("session-b",key));
    assertEquals(a,AuthSecurity.CookieAuth.csrfFor("session-a",key));
  }
}

