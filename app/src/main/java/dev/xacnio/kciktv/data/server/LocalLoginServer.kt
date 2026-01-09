package dev.xacnio.kciktv.data.server

import android.util.Log
import dev.xacnio.kciktv.data.repository.AuthRepository
import dev.xacnio.kciktv.data.repository.LoginResult
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.InputStream
import java.util.UUID

class LocalLoginServer(
    private val port: Int,
    private val authRepository: AuthRepository,
    private val onLoginSuccess: (String, String, String?) -> Unit
) : NanoHTTPD(port) {

    private val sessionToken = UUID.randomUUID().toString()

    fun getLoginUrl(ipAddress: String): String {
        return "http://$ipAddress:$port/login?session=$sessionToken"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            uri == "/login" && method == Method.GET -> {
                val params = session.parameters
                if (params["session"]?.firstOrNull() == sessionToken) {
                    newFixedLengthResponse(Response.Status.OK, "text/html", getHtmlPage())
                } else {
                    newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid session")
                }
            }
            uri == "/do-login" && method == Method.POST -> handleDoLogin(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    private fun handleDoLogin(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val postData = files["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No data")
        
        val json = JSONObject(postData)
        val email = json.getString("email")
        val password = json.getString("password")
        val otp = json.optString("otp").takeIf { it.isNotEmpty() }

        // Perform login on a different thread
        val responseJson = JSONObject()
        
        // This is a bit tricky because we need to wait for the login result to respond to the HTTP request
        // Using a simple blocking call since NanoHTTPD is already on a separate thread per request
        var result: LoginResult? = null
        val lock = Object()

        CoroutineScope(Dispatchers.IO).launch {
            result = authRepository.login(email, password, otp)
            synchronized(lock) {
                lock.notify()
            }
        }

        synchronized(lock) {
            if (result == null) {
                lock.wait(30000) // Wait max 30 seconds
            }
        }

        return when (val res = result) {
            is LoginResult.Success -> {
                onLoginSuccess(res.token, res.user?.username ?: email, res.user?.profilePic)
                responseJson.put("status", "success")
                newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
            }
            is LoginResult.TwoFARequired -> {
                responseJson.put("status", "2fa_required")
                newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
            }
            is LoginResult.Error -> {
                responseJson.put("status", "error")
                responseJson.put("message", res.message)
                newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
            }
            else -> {
                responseJson.put("status", "timeout")
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", responseJson.toString())
            }
        }
    }

    private fun getHtmlPage(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>KcikTV Login</title>
                <style>
                    body { font-family: -apple-system, sans-serif; background: #0A0E17; color: white; padding: 20px; display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 100vh; margin: 0; }
                    .card { background: #141A26; padding: 30px; border-radius: 16px; width: 100%; max-width: 350px; box-shadow: 0 10px 40px rgba(0,0,0,0.8); border: 1px solid #1E2736; }
                    .logo { color: #53FC18; font-size: 28px; font-weight: bold; margin-bottom: 20px; text-align: center; }
                    .logo span { color: #00F2FF; }
                    label { display: block; margin-top: 15px; color: #8F9BB3; font-size: 14px; }
                    input { width: 100%; padding: 12px; margin-top: 5px; border-radius: 8px; border: 1px solid #2E3A59; background: #10141D; color: white; box-sizing: border-box; }
                    input:focus { border-color: #00F2FF; outline: none; }
                    button { width: 100%; padding: 14px; margin-top: 25px; border-radius: 8px; border: none; background: linear-gradient(135deg, #53FC18 0%, #00F2FF 100%); color: #0A0E17; font-weight: bold; font-size: 16px; cursor: pointer; transition: transform 0.2s; }
                    button:active { transform: scale(0.98); }
                    button:disabled { background: #2E3A59; color: #666; }
                    .error { color: #FF4D4D; margin-top: 15px; font-size: 14px; display: none; }
                    #otpSection { display: none; }
                    .loader { border: 3px solid #141A26; border-top: 3px solid #00F2FF; border-radius: 50%; width: 20px; height: 20px; animation: spin 1s linear infinite; display: inline-block; vertical-align: middle; margin-right: 10px; }
                    @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
                    #status { display: none; color: #00F2FF; margin-top: 15px; }
                </style>
            </head>
            <body>
                <div class="card" id="loginForm">
                    <div class="logo">Kcik<span>TV</span></div>
                    <div id="inputSection">
                        <label>Email or Username</label>
                        <input type="text" id="email" placeholder="user@mail.com">
                        
                        <label>Password</label>
                        <input type="password" id="password" placeholder="••••••••">
                        
                        <div id="otpSection">
                            <label>2FA Code (OTP)</label>
                            <input type="text" id="otp" placeholder="123456" maxlength="6">
                        </div>
                        
                        <div id="error" class="error"></div>
                        
                        <button id="loginBtn">Login</button>
                    </div>
                    <div id="status" style="text-align: center;">
                        <div class="loader"></div>
                        Logging in...
                    </div>
                </div>
                <div id="success" style="display:none; text-align: center;">
                    <div class="logo" style="font-size: 48px;">✓</div>
                    <h2>Success!</h2>
                    <p>You can go back to the TV screen.</p>
                </div>

                <script>
                    const loginBtn = document.getElementById('loginBtn');
                    const emailInput = document.getElementById('email');
                    const passwordInput = document.getElementById('password');
                    const otpInput = document.getElementById('otp');
                    const otpSection = document.getElementById('otpSection');
                    const errorDiv = document.getElementById('error');
                    const inputSection = document.getElementById('inputSection');
                    const statusSection = document.getElementById('status');

                    loginBtn.addEventListener('click', async () => {
                        const email = emailInput.value;
                        const password = passwordInput.value;
                        const otp = otpInput.value;

                        if (!email || !password) {
                            showError('Email and password are required');
                            return;
                        }

                        loginBtn.disabled = true;
                        inputSection.style.opacity = '0.5';
                        statusSection.style.display = 'block';
                        errorDiv.style.display = 'none';

                        try {
                            const response = await fetch('/do-login', {
                                method: 'POST',
                                body: JSON.stringify({ email, password, otp })
                            });
                            const result = await response.json();

                            if (result.status === 'success') {
                                document.getElementById('loginForm').style.display = 'none';
                                document.getElementById('success').style.display = 'block';
                            } else if (result.status === '2fa_required') {
                                otpSection.style.display = 'block';
                                showError('Please enter the 2FA code');
                                loginBtn.disabled = false;
                                inputSection.style.opacity = '1';
                                statusSection.style.display = 'none';
                                otpInput.focus();
                            } else {
                                showError(result.message || 'Login failed');
                                loginBtn.disabled = false;
                                inputSection.style.opacity = '1';
                                statusSection.style.display = 'none';
                            }
                        } catch (e) {
                            showError('An error occurred: ' + e.message);
                            loginBtn.disabled = false;
                            inputSection.style.opacity = '1';
                            statusSection.style.display = 'none';
                        }
                    });

                    function showError(msg) {
                        errorDiv.innerText = msg;
                        errorDiv.style.display = 'block';
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
