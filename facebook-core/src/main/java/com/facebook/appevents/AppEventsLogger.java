/**
 * Copyright (c) 2014-present, Facebook, Inc. All rights reserved.
 *
 * You are hereby granted a non-exclusive, worldwide, royalty-free license to use,
 * copy, modify, and distribute this software in source code or binary form for use
 * in connection with the web services and APIs provided by Facebook.
 *
 * As with any software that integrates with the Facebook platform, your use of
 * this software is subject to the Facebook Developer Principles and Policies
 * [http://developers.facebook.com/policy/]. This copyright notice shall be
 * included in all copies or substantial portions of the software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.facebook.appevents;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.util.Log;
import android.webkit.WebView;
import bolts.AppLinks;

import com.facebook.AccessToken;
import com.facebook.FacebookException;
import com.facebook.FacebookSdk;
import com.facebook.GraphRequest;
import com.facebook.HttpMethod;
import com.facebook.LoggingBehavior;
import com.facebook.appevents.internal.ActivityLifecycleTracker;
import com.facebook.appevents.internal.AutomaticAnalyticsLogger;
import com.facebook.appevents.internal.Constants;
import com.facebook.internal.AnalyticsEvents;
import com.facebook.internal.AttributionIdentifiers;
import com.facebook.internal.BundleJSONConverter;
import com.facebook.internal.FetchedAppGateKeepersManager;
import com.facebook.internal.FetchedAppSettingsManager;
import com.facebook.internal.Logger;
import com.facebook.internal.Utility;
import com.facebook.internal.Validate;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * The AppEventsLogger class allows the developer to log various types of events back to Facebook.  In order to log
 * events, the app must create an instance of this class via a {@link #newLogger newLogger} method, and then call
 * the various "log" methods off of that.
 * </p>
 * <p>
 * This client-side event logging is then available through Facebook App Insights
 * and for use with Facebook Ads conversion tracking and optimization.
 * </p>
 * <p>
 * The AppEventsLogger class has a few related roles:
 * </p>
 * <ul>
 * <li>
 * Logging predefined and application-defined events to Facebook App Insights with a
 * numeric value to sum across a large number of events, and an optional set of key/value
 * parameters that define "segments" for this event (e.g., 'purchaserStatus' : 'frequent', or
 * 'gamerLevel' : 'intermediate').  These events may also be used for ads conversion tracking,
 * optimization, and other ads related targeting in the future.
 * </li>
 * <li>
 * Methods that control the way in which events are flushed out to the Facebook servers.
 * </li>
 * </ul>
 * <p>
 * Here are some important characteristics of the logging mechanism provided by AppEventsLogger:
 * <ul>
 * <li>
 * Events are not sent immediately when logged.  They're cached and flushed out to the
 * Facebook servers in a number of situations:
 * <ul>
 * <li>when an event count threshold is passed (currently 100 logged events).</li>
 * <li>when a time threshold is passed (currently 15 seconds).</li>
 * <li>when an app has gone to background and is then brought back to the foreground.</li>
 * </ul>
 * <li>
 * Events will be accumulated when the app is in a disconnected state, and sent when the connection
 * is restored and one of the above 'flush' conditions are met.
 * </li>
 * <li>
 * The AppEventsLogger class is intended to be used from the thread it was created on.  Multiple
 * AppEventsLoggers may be created on other threads if desired.
 * </li>
 * <li>
 * The developer can call the setFlushBehavior method to force the flushing of events to only
 * occur on an explicit call to the `flush` method.
 * </li>
 * <li>
 * The developer can turn on console debug output for event logging and flushing to the server by
 * calling FacebookSdk.addLoggingBehavior(LoggingBehavior.APP_EVENTS);
 * </li>
 * </ul>
 * </p>
 * <p>
 * Some things to note when logging events:
 * <ul>
 * <li>
 * There is a limit on the number of unique event names an app can use, on the order of 1000.
 * </li>
 * <li>
 * There is a limit to the number of unique parameter names in the provided parameters that can
 * be used per event, on the order of 25.  This is not just for an individual call, but for all
 * invocations for that eventName.
 * </li>
 * <li>
 * Event names and parameter names must be between 2 and 40
 * characters, and must consist of alphanumeric characters, _, -, or spaces.
 * </li>
 * <li>
 * The length of each parameter value can be no more than on the order of 100 characters.
 * </li>
 * </ul>
 * </p>
 */
public class AppEventsLogger {
    // Enums

    /**
     * Controls when an AppEventsLogger sends log events to the server
     */
    public enum FlushBehavior {
        /**
         * Flush automatically: periodically (every 15 seconds or after every 100 events), and
         * always at app reactivation. This is the default value.
         */
        AUTO,

        /**
         * Only flush when AppEventsLogger.flush() is explicitly invoked.
         */
        EXPLICIT_ONLY,
    }

    /**
     * Product availability for Product Catalog product item update
     */
    public enum ProductAvailability {
        /**
         * Item ships immediately
         */
        IN_STOCK,
        /**
         * No plan to restock
         */
        OUT_OF_STOCK,
        /**
         * Available in future
         */
        PREORDER,
        /**
         * Ships in 1-2 weeks
         */
        AVALIABLE_FOR_ORDER,
        /**
         * Discontinued
         */
        DISCONTINUED,
    }

    /**
     * Product condition for Product Catalog product item update
     */
    public enum ProductCondition {
        NEW,
        REFURBISHED,
        USED,
    }

    // Constants
    private static final String TAG = AppEventsLogger.class.getCanonicalName();

    private static final int APP_SUPPORTS_ATTRIBUTION_ID_RECHECK_PERIOD_IN_SECONDS = 60 * 60 * 24;
    private static final int FLUSH_APP_SESSION_INFO_IN_SECONDS = 30;

    public static final String APP_EVENT_PREFERENCES = "com.facebook.sdk.appEventPreferences";

    private static final String SOURCE_APPLICATION_HAS_BEEN_SET_BY_THIS_INTENT =
            "_fbSourceApplicationHasBeenSet";

    private static final String PUSH_PAYLOAD_KEY = "fb_push_payload";
    private static final String PUSH_PAYLOAD_CAMPAIGN_KEY = "campaign";

    private static final String APP_EVENT_NAME_PUSH_OPENED = "fb_mobile_push_opened";
    private static final String APP_EVENT_PUSH_PARAMETER_CAMPAIGN = "fb_push_campaign";
    private static final String APP_EVENT_PUSH_PARAMETER_ACTION = "fb_push_action";

    // Instance member variables
    private final String contextName;
    private final AccessTokenAppIdPair accessTokenAppId;

    private static ScheduledThreadPoolExecutor backgroundExecutor;
    private static FlushBehavior flushBehavior = FlushBehavior.AUTO;
    private static Object staticLock = new Object();
    private static String anonymousAppDeviceGUID;
    private static String sourceApplication;
    private static boolean isOpenedByAppLink;
    private static boolean isActivateAppEventRequested;
    private static String pushNotificationsRegistrationId;

    /**
     * Notifies the events system that the app has launched and activate and deactivate events
     * should start being logged automatically. By default this function is called automatically
     * from sdkInitialize() flow. In case 'com.facebook.sdk.AutoLogAppEventsEnabled' manifest
     * setting is set to false, it should typically be called from the OnCreate method
     * of you application.
     *
     * @param application The running application
     */
    public static void activateApp(Application application) {
        activateApp(application, null);
    }

    /**
     * Notifies the events system that the app has launched and activate and deactivate events
     * should start being logged automatically. By default this function is called automatically
     * from sdkInitialize() flow. In case 'com.facebook.sdk.AutoLogAppEventsEnabled' manifest
     * setting is set to false, it should typically be called from the OnCreate method
     * of you application.
     *
     * Call this if you wish to use a different Application ID then the one specified in the
     * Facebook SDK.
     *
     * @param application The running application
     * @param applicationId The application id used to log activate/deactivate events.
     */
    public static void activateApp(Application application, String applicationId) {
        if (!FacebookSdk.isInitialized()) {
            throw new FacebookException("The Facebook sdk must be initialized before calling " +
                    "activateApp");
        }

        AnalyticsUserIDStore.initStore();
        UserDataStore.initStore();

        if (applicationId == null) {
            applicationId = FacebookSdk.getApplicationId();
        }

        // activateApp supersedes publishInstall in the public API, so we need to explicitly invoke
        // it, since the server can't reliably infer install state for all conditions of an app
        // activate.
        FacebookSdk.publishInstallAsync(application, applicationId);

        // Will do nothing in case AutoLogAppEventsEnabled is true, as we already started the
        // tracking as part of sdkInitialize() flow
        ActivityLifecycleTracker.startTracking(application, applicationId);
    }

    /**
     * Notifies the events system that the app has launched & logs an activatedApp event.  Should be
     * called whenever your app becomes active, typically in the onResume() method of each
     * long-running Activity of your app.
     * <p/>
     * Use this method if your application ID is stored in application metadata, otherwise see
     * {@link AppEventsLogger#activateApp(android.content.Context, String)}.
     *
     * @param context Used to access the applicationId and the attributionId for non-authenticated
     *                users.
     * @deprecated Use {@link AppEventsLogger#activateApp(Application)}
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public static void activateApp(Context context) {
        if (ActivityLifecycleTracker.isTracking()) {
            Log.w(TAG, "activateApp events are being logged automatically. " +
                    "There's no need to call activateApp explicitly, this is safe to remove.");
            return;
        }

        FacebookSdk.sdkInitialize(context);
        activateApp(context, Utility.getMetadataApplicationId(context));
    }

    /**
     * Notifies the events system that the app has launched & logs an activatedApp event.  Should be
     * called whenever your app becomes active, typically in the onResume() method of each
     * long-running Activity of your app.
     *
     * @param context       Used to access the attributionId for non-authenticated users.
     * @param applicationId The specific applicationId to report the activation for.
     * @deprecated Use {@link AppEventsLogger#activateApp(Application)}
     */
    @Deprecated
    public static void activateApp(Context context, String applicationId) {
        if (ActivityLifecycleTracker.isTracking()) {
            Log.w(TAG, "activateApp events are being logged automatically. " +
                    "There's no need to call activateApp explicitly, this is safe to remove.");
            return;
        }

        if (context == null || applicationId == null) {
            throw new IllegalArgumentException("Both context and applicationId must be non-null");
        }

        AnalyticsUserIDStore.initStore();
        UserDataStore.initStore();

        if ((context instanceof Activity)) {
            setSourceApplication((Activity) context);
        } else {
          // If context is not an Activity, we cannot get intent nor calling activity.
          resetSourceApplication();
          Utility.logd(AppEventsLogger.class.getName(),
              "To set source application the context of activateApp must be an instance of" +
                      " Activity");
        }

        // activateApp supersedes publishInstall in the public API, so we need to explicitly invoke
        // it, since the server can't reliably infer install state for all conditions of an app
        // activate.
        FacebookSdk.publishInstallAsync(context, applicationId);

        final AppEventsLogger logger = new AppEventsLogger(context, applicationId, null);
        final long eventTime = System.currentTimeMillis();
        final String sourceApplicationInfo = getSourceApplication();
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                logger.logAppSessionResumeEvent(eventTime, sourceApplicationInfo);
            }
        });
    }

    /**
     * Notifies the events system that the app has been deactivated (put in the background) and
     * tracks the application session information. Should be called whenever your app becomes
     * inactive, typically in the onPause() method of each long-running Activity of your app.
     *
     * Use this method if your application ID is stored in application metadata, otherwise see
     * {@link AppEventsLogger#deactivateApp(android.content.Context, String)}.
     *
     * @param context Used to access the applicationId and the attributionId for non-authenticated
     *                users.
     * @deprecated When using {@link AppEventsLogger#activateApp(Application)} deactivate app will
     * be logged automatically.
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public static void deactivateApp(Context context) {
        if (ActivityLifecycleTracker.isTracking()) {
            Log.w(TAG, "deactivateApp events are being logged automatically. " +
                    "There's no need to call deactivateApp, this is safe to remove.");
            return;
        }

        deactivateApp(context, Utility.getMetadataApplicationId(context));
    }

    /**
     * Notifies the events system that the app has been deactivated (put in the background) and
     * tracks the application session information. Should be called whenever your app becomes
     * inactive, typically in the onPause() method of each long-running Activity of your app.
     *
     * @param context       Used to access the attributionId for non-authenticated users.
     * @param applicationId The specific applicationId to track session information for.
     * @deprecated When using {@link AppEventsLogger#activateApp(Application)} deactivate app will
     * be logged automatically.
     */
    @Deprecated
    public static void deactivateApp(Context context, String applicationId) {
        if (ActivityLifecycleTracker.isTracking()) {
            Log.w(TAG, "deactivateApp events are being logged automatically. " +
                    "There's no need to call deactivateApp, this is safe to remove.");
            return;
        }

        if (context == null || applicationId == null) {
            throw new IllegalArgumentException("Both context and applicationId must be non-null");
        }

        resetSourceApplication();

        final AppEventsLogger logger = new AppEventsLogger(context, applicationId, null);
        final long eventTime = System.currentTimeMillis();
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                logger.logAppSessionSuspendEvent(eventTime);
            }
        });
    }

    private void logAppSessionResumeEvent(long eventTime, String sourceApplicationInfo) {
        PersistedAppSessionInfo.onResume(
                FacebookSdk.getApplicationContext(),
                accessTokenAppId,
                this,
                eventTime,
                sourceApplicationInfo);
    }

    private void logAppSessionSuspendEvent(long eventTime) {
        PersistedAppSessionInfo.onSuspend(
                FacebookSdk.getApplicationContext(),
                accessTokenAppId,
                this,
                eventTime);
    }

    /**
     * Notifies the events system which internal SDK Libraries,
     * and some specific external Libraries that the app is utilizing.
     * This is called internally and does NOT need to be called externally.
     *
     * @param context The Context
     * @param applicationId The String applicationId
     */
    public static void initializeLib(Context context, String applicationId) {
        if (!FacebookSdk.getAutoLogAppEventsEnabled()) {
            return;
        }
        final AppEventsLogger logger = new AppEventsLogger(context, applicationId, null);
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Bundle params = new Bundle();

                // internal SDK Libraries
                try {
                    Class.forName("com.facebook.core.Core");
                    params.putInt("core_lib_included", 1);
                } catch (ClassNotFoundException ignored) { /* no op */ }
                try {
                    Class.forName("com.facebook.login.Login");
                    params.putInt("login_lib_included", 1);
                } catch (ClassNotFoundException ignored) { /* no op */ }
                try {
                    Class.forName("com.facebook.share.Share");
                    params.putInt("share_lib_included", 1);
                } catch (ClassNotFoundException ignored) { /* no op */ }
                try {
                    Class.forName("com.facebook.places.Places");
                    params.putInt("places_lib_included", 1);
                } catch (ClassNotFoundException ignored) { /* no op */ }
                try {
                    Class.forName("com.facebook.messenger.Messenger");
                    params.putInt("messenger_lib_included", 1);
                } catch (ClassNotFoundException ignored) { /* no op */ }
                try {
                    Class.forName("com.facebook.applinks.AppLinks");
                    params.putInt("applinks_lib_included", 1);
                } catch (ClassNotFoundException ignored) { /* no op */ }
                try {
                    Class.forName("com.facebook.marketing.Marketing");
                    params.putInt("marketing_lib_included", 1);
                } catch (ClassNotFoundException ignored) { /* no op */ }
                try {
                    Class.forName("com.facebook.all.All");
                    params.putInt("all_lib_included", 1);
                } catch (ClassNotFoundException ignored) { /* no op */ }

                //  external SDK Libraries
                try {
                    Class.forName("com.android.billingclient.api.BillingClient");
                    params.putInt("billing_client_lib_included", 1);
                } catch (ClassNotFoundException ignored) { /* no op */ }
                try {
                    Class.forName("com.android.vending.billing.IInAppBillingService");
                    params.putInt("billing_service_lib_included", 1);
                } catch (ClassNotFoundException ignored) { /* no op */ }

                logger.logSdkEvent(AnalyticsEvents.EVENT_SDK_INITIALIZE, null, params);
            }
        });
    }

    /**
     * Build an AppEventsLogger instance to log events through.  The Facebook app that these events
     * are targeted at comes from this application's metadata. The application ID used to log events
     * will be determined from the app ID specified in the package metadata.
     *
     * @param context Used to access the applicationId and the attributionId for non-authenticated
     *                users.
     * @return AppEventsLogger instance to invoke log* methods on.
     */
    public static AppEventsLogger newLogger(Context context) {
        return new AppEventsLogger(context, null, null);
    }

    /**
     * Build an AppEventsLogger instance to log events through.
     *
     * @param context Used to access the attributionId for non-authenticated users.
     * @param accessToken Access token to use for logging events. If null, the active access token
     *                    will be used, if any; if not the logging will happen against the default
     *                    app ID specified in the package metadata.
     */
    public static AppEventsLogger newLogger(Context context, AccessToken accessToken) {
        return new AppEventsLogger(context, null, accessToken);
    }

    /**
     * Build an AppEventsLogger instance to log events through.
     *
     * @param context       Used to access the attributionId for non-authenticated users.
     * @param applicationId Explicitly specified Facebook applicationId to log events against.  If
     *                      null, the default app ID specified in the package metadata will be
     *                      used.
     * @param accessToken   Access token to use for logging events. If null, the active access token
     *                      will be used, if any; if not the logging will happen against the default
     *                      app ID specified in the package metadata.
     * @return AppEventsLogger instance to invoke log* methods on.
     */
    public static AppEventsLogger newLogger(
            Context context,
            String applicationId,
            AccessToken accessToken) {
        return new AppEventsLogger(context, applicationId, accessToken);
    }

    /**
     * Build an AppEventsLogger instance to log events that are attributed to the application but
     * not to any particular Session.
     *
     * @param context       Used to access the attributionId for non-authenticated users.
     * @param applicationId Explicitly specified Facebook applicationId to log events against.  If
     *                      null, the default app ID specified in the package metadata will be
     *                      used.
     * @return AppEventsLogger instance to invoke log* methods on.
     */
    public static AppEventsLogger newLogger(Context context, String applicationId) {
        return new AppEventsLogger(context, applicationId, null);
    }

    /**
     * The action used to indicate that a flush of app events has occurred. This should
     * be used as an action in an IntentFilter and BroadcastReceiver registered with
     * the {@link android.support.v4.content.LocalBroadcastManager}.
     */
    public static final String ACTION_APP_EVENTS_FLUSHED = "com.facebook.sdk.APP_EVENTS_FLUSHED";

    public static final String APP_EVENTS_EXTRA_NUM_EVENTS_FLUSHED =
            "com.facebook.sdk.APP_EVENTS_NUM_EVENTS_FLUSHED";
    public static final String APP_EVENTS_EXTRA_FLUSH_RESULT =
            "com.facebook.sdk.APP_EVENTS_FLUSH_RESULT";

    /**
     * Access the behavior that AppEventsLogger uses to determine when to flush logged events to the
     * server. This setting applies to all instances of AppEventsLogger.
     *
     * @return Specified flush behavior.
     */
    public static FlushBehavior getFlushBehavior() {
        synchronized (staticLock) {
            return flushBehavior;
        }
    }

    /**
     * Set the behavior that this AppEventsLogger uses to determine when to flush logged events to
     * the server. This setting applies to all instances of AppEventsLogger.
     *
     * @param flushBehavior the desired behavior.
     */
    public static void setFlushBehavior(FlushBehavior flushBehavior) {
        synchronized (staticLock) {
            AppEventsLogger.flushBehavior = flushBehavior;
        }
    }

    /**
     * Log an app event with the specified name.
     *
     * @param eventName eventName used to denote the event.  Choose amongst the EVENT_NAME_*
     *                  constants in {@link AppEventsConstants} when possible.  Or create your own
     *                  if none of the EVENT_NAME_* constants are applicable. Event names should be
     *                  40 characters or less, alphanumeric, and can include spaces, underscores or
     *                  hyphens, but must not have a space or hyphen as the first character.  Any
     *                  given app should have no more than 1000 distinct event names.
     */
    public void logEvent(String eventName) {
        logEvent(eventName, null);
    }

    /**
     * Log an app event with the specified name and the supplied value.
     *
     * @param eventName  eventName used to denote the event.  Choose amongst the EVENT_NAME_*
     *                   constants in {@link AppEventsConstants} when possible.  Or create your own
     *                   if none of the EVENT_NAME_* constants are applicable. Event names should be
     *                   40 characters or less, alphanumeric, and can include spaces, underscores or
     *                   hyphens, but must not have a space or hyphen as the first character.  Any
     *                   given app should have no more than 1000 distinct event names. * @param
     *                   eventName
     * @param valueToSum a value to associate with the event which will be summed up in Insights for
     *                   across all instances of the event, so that average values can be
     *                   determined, etc.
     */
    public void logEvent(String eventName, double valueToSum) {
        logEvent(eventName, valueToSum, null);
    }

    /**
     * Log an app event with the specified name and set of parameters.
     *
     * @param eventName  eventName used to denote the event.  Choose amongst the EVENT_NAME_*
     *                   constants in {@link AppEventsConstants} when possible.  Or create your own
     *                   if none of the EVENT_NAME_* constants are applicable. Event names should be
     *                   40 characters or less, alphanumeric, and can include spaces, underscores or
     *                   hyphens, but must not have a space or hyphen as the first character.  Any
     *                   given app should have no more than 1000 distinct event names.
     * @param parameters A Bundle of parameters to log with the event.  Insights will allow looking
     *                   at the logs of these events via different parameter values.  You can log on
     *                   the order of 25 parameters with each distinct eventName.  It's advisable to
     *                   limit the number of unique values provided for each parameter in the
     *                   thousands.  As an example, don't attempt to provide a unique
     *                   parameter value for each unique user in your app.  You won't get meaningful
     *                   aggregate reporting on so many parameter values.  The values in the bundles
     *                   should be Strings or numeric values.
     */
    public void logEvent(String eventName, Bundle parameters) {
        logEvent(
            eventName,
            null,
            parameters,
            false,
            ActivityLifecycleTracker.getCurrentSessionGuid());
    }

    /**
     * Log an app event with the specified name, supplied value, and set of parameters.
     *
     * @param eventName  eventName used to denote the event.  Choose amongst the EVENT_NAME_*
     *                   constants in {@link AppEventsConstants} when possible.  Or create your own
     *                   if none of the EVENT_NAME_* constants are applicable. Event names should be
     *                   40 characters or less, alphanumeric, and can include spaces, underscores or
     *                   hyphens, but must not have a space or hyphen as the first character.  Any
     *                   given app should have no more than 1000 distinct event names.
     * @param valueToSum a value to associate with the event which will be summed up in Insights for
     *                   across all instances of the event, so that average values can be
     *                   determined, etc.
     * @param parameters A Bundle of parameters to log with the event.  Insights will allow looking
     *                   at the logs of these events via different parameter values.  You can log on
     *                   the order of 25 parameters with each distinct eventName.  It's advisable to
     *                   limit the number of unique values provided for each parameter in the
     *                   thousands.  As an example, don't attempt to provide a unique
     *                   parameter value for each unique user in your app.  You won't get meaningful
     *                   aggregate reporting on so many parameter values.  The values in the bundles
     *                   should be Strings or numeric values.
     */
    public void logEvent(String eventName, double valueToSum, Bundle parameters) {
        logEvent(
            eventName,
            valueToSum,
            parameters,
            false,
            ActivityLifecycleTracker.getCurrentSessionGuid());
    }

    /**
     * Logs a purchase event with Facebook, in the specified amount and with the specified
     * currency.
     *
     * @param purchaseAmount Amount of purchase, in the currency specified by the 'currency'
     *                       parameter. This value will be rounded to the thousandths place (e.g.,
     *                       12.34567 becomes 12.346).
     * @param currency       Currency used to specify the amount.
     */
    public void logPurchase(BigDecimal purchaseAmount, Currency currency) {
        if (AutomaticAnalyticsLogger.isImplicitPurchaseLoggingEnabled()) {
            Log.w(TAG, "You are logging purchase events while auto-logging of in-app purchase is " +
                    "enabled in the SDK. Make sure you don't log duplicate events");
        }
        logPurchase(purchaseAmount, currency, null, false);
    }

    /**
     * Logs a purchase event with Facebook explicitly, in the specified amount and with the
     * specified currency. Additional detail about the purchase can be passed in through the
     * parameters bundle.
     * @param purchaseAmount Amount of purchase, in the currency specified by the 'currency'
     *                       parameter. This value will be rounded to the thousandths place (e.g.,
     *                       12.34567 becomes 12.346).
     * @param currency       Currency used to specify the amount.
     * @param parameters     Arbitrary additional information for describing this event. This should
     *                       have no more than 24 entries, and keys should be mostly consistent from
     *                       one purchase event to the next.
     */
    public void logPurchase(
            BigDecimal purchaseAmount, Currency currency, Bundle parameters) {
        if (AutomaticAnalyticsLogger.isImplicitPurchaseLoggingEnabled()) {
            Log.w(TAG, "You are logging purchase events while auto-logging of in-app purchase is " +
                    "enabled in the SDK. Make sure you don't log duplicate events");
        }
        logPurchase(purchaseAmount, currency, parameters, false);
    }

    /**
     *@deprecated Use {@link
     *  AppEventsLogger#logPurchase(
     *  java.math.BigDecimal, java.util.Currency, android.os.Bundle)} instead.
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public void logPurchaseImplicitly(
            BigDecimal purchaseAmount, Currency currency, Bundle parameters) {
        String errMsg = "Function logPurchaseImplicitly() is deprecated " +
                "and your purchase events cannot be logged with this function. ";

        if (AutomaticAnalyticsLogger.isImplicitPurchaseLoggingEnabled()) {
            errMsg += "Auto-logging of in-app purchase has been enabled in the SDK, " +
                    "so you don't have to manually log purchases";
        } else {
            errMsg += "Please use logPurchase() function instead.";
        }

        Log.e(TAG, errMsg);
    }

    protected void logPurchaseImplicitlyInternal(
            BigDecimal purchaseAmount, Currency currency, Bundle parameters) {
        logPurchase(purchaseAmount, currency, parameters, true);
    }

    /**
     * Logs a purchase event with Facebook, in the specified amount and with the specified currency.
     * Additional detail about the purchase can be passed in through the parameters bundle.
     *
     * @param purchaseAmount Amount of purchase, in the currency specified by the 'currency'
     *                       parameter. This value will be rounded to the thousandths place (e.g.,
     *                       12.34567 becomes 12.346).
     * @param currency       Currency used to specify the amount.
     * @param parameters     Arbitrary additional information for describing this event. This should
     *                       have no more than 24 entries, and keys should be mostly consistent from
     *                       one purchase event to the next.
     */
    @SuppressWarnings("deprecation")
    private void logPurchase(
            BigDecimal purchaseAmount,
            Currency currency,
            Bundle parameters,
            boolean isImplicitlyLogged) {

        if (purchaseAmount == null) {
            notifyDeveloperError("purchaseAmount cannot be null");
            return;
        } else if (currency == null) {
            notifyDeveloperError("currency cannot be null");
            return;
        }

        if (parameters == null) {
            parameters = new Bundle();
        }
        parameters.putString(AppEventsConstants.EVENT_PARAM_CURRENCY, currency.getCurrencyCode());

        logEvent(
                AppEventsConstants.EVENT_NAME_PURCHASED,
                purchaseAmount.doubleValue(),
                parameters,
                isImplicitlyLogged,
                ActivityLifecycleTracker.getCurrentSessionGuid());
        eagerFlush();
    }

    /**
     * Logs an app event that tracks that the application was open via Push Notification.
     * @param payload Notification payload received.
     */
    public void logPushNotificationOpen(Bundle payload) {
        logPushNotificationOpen(payload, null);
    }

    /**
     * Logs an app event that tracks that the application was open via Push Notification.
     * @param payload Notification payload received.
     */
    public void logPushNotificationOpen(Bundle payload, String action) {
        String campaignId = null;
        try {
            String payloadString = payload.getString(PUSH_PAYLOAD_KEY);
            if (Utility.isNullOrEmpty(payloadString)) {
                return; // Ignore the payload if no fb push payload is present.
            }

            JSONObject facebookPayload = new JSONObject(payloadString);
            campaignId = facebookPayload.getString(PUSH_PAYLOAD_CAMPAIGN_KEY);
        } catch (JSONException je) {
            // ignore
        }
        if (campaignId == null) {
            Logger.log(LoggingBehavior.DEVELOPER_ERRORS, TAG,
                "Malformed payload specified for logging a push notification open.");
            return;
        }

        Bundle parameters = new Bundle();
        parameters.putString(APP_EVENT_PUSH_PARAMETER_CAMPAIGN, campaignId);
        if (action != null) {
            parameters.putString(APP_EVENT_PUSH_PARAMETER_ACTION, action);
        }
        logEvent(APP_EVENT_NAME_PUSH_OPENED, parameters);
    }

    /**
     * Uploads product catalog product item as an app event.
     * @param itemID            Unique ID for the item. Can be a variant for a product.
     *                          Max size is 100.
     * @param availability      If item is in stock. Accepted values are:
     *                              in stock - Item ships immediately
     *                              out of stock - No plan to restock
     *                              preorder - Available in future
     *                              available for order - Ships in 1-2 weeks
     *                              discontinued - Discontinued
     * @param condition         Product condition: new, refurbished or used.
     * @param description       Short text describing product. Max size is 5000.
     * @param imageLink         Link to item image used in ad.
     * @param link              Link to merchant's site where someone can buy the item.
     * @param title             Title of item.
     * @param priceAmount       Amount of purchase, in the currency specified by the 'currency'
     *                          parameter. This value will be rounded to the thousandths place
     *                          (e.g., 12.34567 becomes 12.346).
     * @param currency          Currency used to specify the amount.
     * @param gtin              Global Trade Item Number including UPC, EAN, JAN and ISBN
     * @param mpn               Unique manufacture ID for product
     * @param brand             Name of the brand
     *                          Note: Either gtin, mpn or brand is required.
     * @param parameters        Optional fields for deep link specification.
     */
    public void logProductItem(
            String itemID,
            ProductAvailability availability,
            ProductCondition condition,
            String description,
            String imageLink,
            String link,
            String title,
            BigDecimal priceAmount,
            Currency currency,
            String gtin,
            String mpn,
            String brand,
            Bundle parameters
    ) {
        if (itemID == null) {
            notifyDeveloperError("itemID cannot be null");
            return;
        } else if (availability == null) {
            notifyDeveloperError("availability cannot be null");
            return;
        } else if (condition == null) {
            notifyDeveloperError("condition cannot be null");
            return;
        } else if (description == null) {
            notifyDeveloperError("description cannot be null");
            return;
        } else if (imageLink == null) {
            notifyDeveloperError("imageLink cannot be null");
            return;
        } else if (link == null) {
            notifyDeveloperError("link cannot be null");
            return;
        } else if (title == null) {
            notifyDeveloperError("title cannot be null");
            return;
        } else if (priceAmount == null) {
            notifyDeveloperError("priceAmount cannot be null");
            return;
        } else if (currency == null) {
            notifyDeveloperError("currency cannot be null");
            return;
        } else if (gtin == null && mpn == null && brand == null) {
            notifyDeveloperError("Either gtin, mpn or brand is required");
            return;
        }

        if (parameters == null) {
            parameters = new Bundle();
        }
        parameters.putString(Constants.EVENT_PARAM_PRODUCT_ITEM_ID, itemID);
        parameters.putString(Constants.EVENT_PARAM_PRODUCT_AVAILABILITY, availability.name());
        parameters.putString(Constants.EVENT_PARAM_PRODUCT_CONDITION, condition.name());
        parameters.putString(Constants.EVENT_PARAM_PRODUCT_DESCRIPTION, description);
        parameters.putString(Constants.EVENT_PARAM_PRODUCT_IMAGE_LINK, imageLink);
        parameters.putString(Constants.EVENT_PARAM_PRODUCT_LINK, link);
        parameters.putString(Constants.EVENT_PARAM_PRODUCT_TITLE, title);
        parameters.putString(Constants.EVENT_PARAM_PRODUCT_PRICE_AMOUNT,
                priceAmount.setScale(3, BigDecimal.ROUND_HALF_UP).toString());
        parameters.putString(
                Constants.EVENT_PARAM_PRODUCT_PRICE_CURRENCY, currency.getCurrencyCode());
        if (gtin != null) {
            parameters.putString(Constants.EVENT_PARAM_PRODUCT_GTIN, gtin);
        }
        if (mpn != null) {
            parameters.putString(Constants.EVENT_PARAM_PRODUCT_MPN, mpn);
        }
        if (brand != null) {
            parameters.putString(Constants.EVENT_PARAM_PRODUCT_BRAND, brand);
        }

        logEvent(
                AppEventsConstants.EVENT_NAME_PRODUCT_CATALOG_UPDATE,
                parameters);
        eagerFlush();
    }

    /**
     * Explicitly flush any stored events to the server.  Implicit flushes may happen depending on
     * the value of getFlushBehavior.  This method allows for explicit, app invoked flushing.
     */
    public void flush() {
        AppEventQueue.flush(FlushReason.EXPLICIT);
    }

    /**
     * Call this when the consuming Activity/Fragment receives an onStop() callback in order to
     * persist any outstanding events to disk so they may be flushed at a later time. The next
     * flush (explicit or not) will check for any outstanding events and if present, include them
     * in that flush. Note that this call may trigger an I/O operation on the calling thread.
     * Explicit use of this method is necessary.
     */
    public static void onContextStop() {
        // TODO: (v4) add onContextStop() to samples that use the logger.
        AppEventQueue.persistToDisk();
    }

    /**
     * Determines if the logger is valid for the given access token.
     * @param accessToken The access token to check.
     * @return True if the access token is valid for this logger.
     */
    public boolean isValidForAccessToken(AccessToken accessToken) {
        AccessTokenAppIdPair other = new AccessTokenAppIdPair(accessToken);
        return accessTokenAppId.equals(other);
    }

    /**
     * Sets and sends registration id to register the current app for push notifications.
     * @param registrationId RegistrationId received from FCM.
     */
    public static void setPushNotificationsRegistrationId(String registrationId) {
        synchronized (staticLock) {
            if (!Utility.stringsEqualOrEmpty(pushNotificationsRegistrationId, registrationId))
            {
                pushNotificationsRegistrationId = registrationId;

                AppEventsLogger logger = AppEventsLogger.newLogger(
                        FacebookSdk.getApplicationContext());
                // Log implicit push token event and flush logger immediately
                logger.logEvent(AppEventsConstants.EVENT_NAME_PUSH_TOKEN_OBTAINED);
                if (AppEventsLogger.getFlushBehavior() !=
                        AppEventsLogger.FlushBehavior.EXPLICIT_ONLY) {
                    logger.flush();
                }
            }
        }
    }

    static String getPushNotificationsRegistrationId() {
        synchronized (staticLock) {
            return pushNotificationsRegistrationId;
        }
    }

    /**
     *  Intended to be used as part of a hybrid webapp.
     *  If you call this method, the FB SDK will add a new JavaScript interface into your webview.
     *  If the FB Pixel is used within the webview, and references the app ID of this app,  then it
     *  will detect the presence of this injected JavaScript object and pass Pixel events back to
     *  the FB SDK for logging using the AppEvents framework.
     *
     * @param webView The webview to augment with the additional JavaScript behaviour
     * @param context Used to access the applicationId and the attributionId for non-authenticated
     *                users.
     */
    public static void augmentWebView(WebView webView, Context context) {
        String[] parts = Build.VERSION.RELEASE.split("\\.");
        int majorRelease = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
        int minorRelease = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 ||
                majorRelease < 4 || (majorRelease == 4 && minorRelease <= 1)) {
            Logger.log(LoggingBehavior.DEVELOPER_ERRORS, TAG,
                    "augmentWebView is only available for Android SDK version >= 17 on devices " +
                            "running Android >= 4.2");
            return;
        }
        webView.addJavascriptInterface(new FacebookSDKJSInterface(context),
                "fbmq_" + FacebookSdk.getApplicationId());
    }

    /**
     * Sets a user id to associate with all app events. This can be used to associate your own
     * user id with the app events logged from this instance of an application.
     *
     * The user ID will be persisted between application instances.
     *
     * @param userID A User ID
     */
    public static void setUserID(final String userID) {
        AnalyticsUserIDStore.setUserID(userID);
    }

    /**
     * Returns the set user id else null.
     */
    public static String getUserID() {
       return AnalyticsUserIDStore.getUserID();
    }

    /**
     * Clears the currently set user id.
     */
    public static void clearUserID() {
        AnalyticsUserIDStore.setUserID(null);
    }

    /**
     * Sets user data to associate with all app events. All user data are hashed and used to
     * match Facebook user from this instance of an application.
     *
     * The user data will be persisted between application instances.
     *
     * @param userData user data to identify the user. User data should be formated as a bundle of
     *                 data type name and value. Supported data types and names are:
     *                 Email: em
     *                 First Name: fn
     *                 Last Name: ln
     *                 Phone: ph
     *                 Date of Birth: db
     *                 Gender: ge
     *                 City: ct
     *                 State: st
     *                 Zip: zp
     *                 Country: country
     */
    @Deprecated
    public static void setUserData(final Bundle userData) {
        UserDataStore.setUserDataAndHash(userData);
    }

    /**
     * Sets user data to associate with all app events. All user data are hashed and used to
     * match Facebook user from this instance of an application.
     *
     * The user data will be persisted between application instances.
     *
     * @param email user's email
     * @param firstName user's first name
     * @param lastName user's last name
     * @param phone  user's phone
     * @param dateOfBirth user's date of birth
     * @param gender user's gender
     * @param city user's city
     * @param state user's state
     * @param zip user's zip
     * @param country user's country
     */
    public static void setUserData(
            @Nullable final String email,
            @Nullable final String firstName,
            @Nullable final String lastName,
            @Nullable final String phone,
            @Nullable final String dateOfBirth,
            @Nullable final String gender,
            @Nullable final String city,
            @Nullable final String state,
            @Nullable final String zip,
            @Nullable final String country) {
        UserDataStore.setUserDataAndHash(
                email,
                firstName,
                lastName,
                phone,
                dateOfBirth,
                gender,
                city,
                state,
                zip,
                country);
    }

    /**
     * Returns the set user data else null.
     */
    public static String getUserData() {
        return UserDataStore.getHashedUserData();
    }

    /**
     * Clears the current user data
     */
    public static void clearUserData() {
        UserDataStore.clear();
    }

    public static void updateUserProperties(
            Bundle parameters,
            GraphRequest.Callback callback) {
        updateUserProperties(
                parameters,
                FacebookSdk.getApplicationId(),
                callback);
    }

    public static void updateUserProperties(
            final Bundle parameters,
            final String applicationID,
            final GraphRequest.Callback callback) {
        getAnalyticsExecutor().execute(new Runnable() {
            @Override
            public void run() {
                final String userID = getUserID();
                if (userID == null || userID.isEmpty()) {
                    Logger.log(
                          LoggingBehavior.APP_EVENTS,
                          TAG,
                          "AppEventsLogger userID cannot be null or empty");
                    return;
                }

                Bundle userPropertiesParams = new Bundle();
                userPropertiesParams.putString("user_unique_id", userID);
                userPropertiesParams.putBundle("custom_data", parameters);
                // This call must be run on the background thread
                AttributionIdentifiers identifiers =
                        AttributionIdentifiers.getAttributionIdentifiers(
                            FacebookSdk.getApplicationContext());
                if (identifiers != null && identifiers.getAndroidAdvertiserId() != null) {
                    userPropertiesParams.putString(
                            "advertiser_id",
                            identifiers.getAndroidAdvertiserId());
                }

                Bundle data = new Bundle();
                try {
                    JSONObject userData = BundleJSONConverter.convertToJSON(userPropertiesParams);
                    JSONArray dataArray = new JSONArray();
                    dataArray.put(userData);

                    data.putString(
                            "data", dataArray.toString());
                } catch (JSONException ex) {
                    throw new FacebookException("Failed to construct request", ex);
                }

                GraphRequest request = new GraphRequest(
                        AccessToken.getCurrentAccessToken(),
                        String.format(Locale.US, "%s/user_properties", applicationID),
                        data,
                        HttpMethod.POST,
                        callback);
                request.setSkipClientToken(true);
                request.executeAsync();
            }
        });
    }

    /**
     * This method is intended only for internal use by the Facebook SDK and other use is
     * unsupported.
     */
    public void logSdkEvent(String eventName, Double valueToSum, Bundle parameters) {
        logEvent(
            eventName,
            valueToSum,
            parameters,
            true,
            ActivityLifecycleTracker.getCurrentSessionGuid());
    }

    /**
     * Returns the app ID this logger was configured to log to.
     *
     * @return the Facebook app ID
     */
    public String getApplicationId() {
        return accessTokenAppId.getApplicationId();
    }

    //
    // Private implementation
    //

    /**
     * Constructor is private, newLogger() methods should be used to build an instance.
     */
    private AppEventsLogger(Context context, String applicationId, AccessToken accessToken) {
        this(
                Utility.getActivityName(context),
                applicationId,
                accessToken);
    }

    protected AppEventsLogger(
            String activityName,
            String applicationId,
            AccessToken accessToken) {
        Validate.sdkInitialized();
        this.contextName = activityName;

        if (accessToken == null) {
            accessToken = AccessToken.getCurrentAccessToken();
        }

        // If we have a session and the appId passed is null or matches the session's app ID:
        if (AccessToken.isCurrentAccessTokenActive() &&
                (applicationId == null || applicationId.equals(accessToken.getApplicationId()))
                ) {
            accessTokenAppId = new AccessTokenAppIdPair(accessToken);
        } else {
            // If no app ID passed, get it from the manifest:
            if (applicationId == null) {
                applicationId = Utility.getMetadataApplicationId(
                        FacebookSdk.getApplicationContext());
            }
            accessTokenAppId = new AccessTokenAppIdPair(null, applicationId);
        }

        initializeTimersIfNeeded();
    }

    private static void initializeTimersIfNeeded() {
        synchronized (staticLock) {
            if (backgroundExecutor != null) {
                return;
            }
            // Having single runner thread enforces ordered execution of tasks,
            // which matters in some cases e.g. making sure user id is set before
            // trying to update user properties for a given id
            backgroundExecutor = new ScheduledThreadPoolExecutor(1);
        }

        final Runnable attributionRecheckRunnable = new Runnable() {
            @Override
            public void run() {
                Set<String> applicationIds = new HashSet<>();
                for (AccessTokenAppIdPair accessTokenAppId : AppEventQueue.getKeySet()) {
                    applicationIds.add(accessTokenAppId.getApplicationId());
                }

                for (String applicationId : applicationIds) {
                    FetchedAppSettingsManager.queryAppSettings(applicationId, true);
                }
            }
        };

        backgroundExecutor.scheduleAtFixedRate(
                attributionRecheckRunnable,
                0,
                APP_SUPPORTS_ATTRIBUTION_ID_RECHECK_PERIOD_IN_SECONDS,
                TimeUnit.SECONDS
        );
    }

    protected void logEventImplicitly(String eventName,
                                      BigDecimal purchaseAmount,
                                      Currency currency,
                                      Bundle parameters) {
        logEvent(
                eventName,
                purchaseAmount.doubleValue(),
                parameters,
                true,
                ActivityLifecycleTracker.getCurrentSessionGuid());
    }

    private void logEvent(
            String eventName,
            Double valueToSum,
            Bundle parameters,
            boolean isImplicitlyLogged,
            @Nullable final UUID currentSessionId) {
        try {
            AppEvent event = new AppEvent(
                    this.contextName,
                    eventName,
                    valueToSum,
                    parameters,
                    isImplicitlyLogged,
                    ActivityLifecycleTracker.isInBackground(),
                    currentSessionId);
            logEvent(event, accessTokenAppId);
        } catch (JSONException jsonException) {
            // If any of the above failed, just consider this an illegal event.
            Logger.log(LoggingBehavior.APP_EVENTS, "AppEvents",
                    "JSON encoding for app event failed: '%s'", jsonException.toString());

        } catch (FacebookException e) {
            // If any of the above failed, just consider this an illegal event.
            Logger.log(LoggingBehavior.APP_EVENTS, "AppEvents",
                    "Invalid app event: %s", e.toString());
        }

    }

    private static void logEvent(final AppEvent event,
                                 final AccessTokenAppIdPair accessTokenAppId) {
        AppEventQueue.add(accessTokenAppId, event);

        // Make sure Activated_App is always before other app events
        if (!event.getIsImplicit() && !isActivateAppEventRequested) {
            if (event.getName().equals(AppEventsConstants.EVENT_NAME_ACTIVATED_APP)) {
                isActivateAppEventRequested = true;
            } else {
                Logger.log(LoggingBehavior.APP_EVENTS, "AppEvents",
                        "Warning: Please call AppEventsLogger.activateApp(...)" +
                                "from the long-lived activity's onResume() method" +
                                "before logging other app events."
                );
            }
        }
    }

    static void eagerFlush() {
        if (getFlushBehavior() != FlushBehavior.EXPLICIT_ONLY) {
            AppEventQueue.flush(FlushReason.EAGER_FLUSHING_EVENT);
        }
    }

    /**
     * Invoke this method, rather than throwing an Exception, for situations where user/server input
     * might reasonably cause this to occur, and thus don't want an exception thrown at production
     * time, but do want logging notification.
     */
    private static void notifyDeveloperError(String message) {
        Logger.log(LoggingBehavior.DEVELOPER_ERRORS, "AppEvents", message);
    }

    /**
     * Source Application setters and getters
     */
    private static void setSourceApplication(Activity activity) {

        ComponentName callingApplication = activity.getCallingActivity();
        if (callingApplication != null) {
            String callingApplicationPackage = callingApplication.getPackageName();
            if (callingApplicationPackage.equals(activity.getPackageName())) {
                // open by own app.
                resetSourceApplication();
                return;
            }
            sourceApplication = callingApplicationPackage;
        }

        // Tap icon to open an app will still get the old intent if the activity was opened by an
        // intent before. Introduce an extra field in the intent to force clear the
        // sourceApplication.
        Intent openIntent = activity.getIntent();
        if (openIntent == null ||
                openIntent.getBooleanExtra(SOURCE_APPLICATION_HAS_BEEN_SET_BY_THIS_INTENT, false)) {
            resetSourceApplication();
            return;
        }

        Bundle appLinkData = AppLinks.getAppLinkData(openIntent);

        if (appLinkData == null) {
            resetSourceApplication();
            return;
        }

        isOpenedByAppLink = true;

        Bundle appLinkReferrerData = appLinkData.getBundle("referer_app_link");

        if (appLinkReferrerData == null) {
            sourceApplication = null;
            return;
        }

        String appLinkReferrerPackage = appLinkReferrerData.getString("package");
        sourceApplication = appLinkReferrerPackage;

        // Mark this intent has been used to avoid use this intent again and again.
        openIntent.putExtra(SOURCE_APPLICATION_HAS_BEEN_SET_BY_THIS_INTENT, true);

        return;
    }

    static void setSourceApplication(String applicationPackage, boolean openByAppLink) {
        sourceApplication = applicationPackage;
        isOpenedByAppLink = openByAppLink;
    }

    static String getSourceApplication() {
        String openType = "Unclassified";
        if (isOpenedByAppLink) {
            openType = "Applink";
        }
        if (sourceApplication != null) {
            return openType + "(" + sourceApplication + ")";
        }
        return openType;
    }

    static void resetSourceApplication() {
        sourceApplication = null;
        isOpenedByAppLink = false;
    }

    static Executor getAnalyticsExecutor() {
        if (backgroundExecutor == null) {
            initializeTimersIfNeeded();
        }

        return backgroundExecutor;
    }

    /**
     * Each app/device pair gets an GUID that is sent back with App Events and persisted with this
     * app/device pair.
     * @param context The application context.
     * @return The GUID for this app/device pair.
     */
    public static String getAnonymousAppDeviceGUID(Context context) {

        if (anonymousAppDeviceGUID == null) {
            synchronized (staticLock) {
                if (anonymousAppDeviceGUID == null) {

                    SharedPreferences preferences = context.getSharedPreferences(
                            APP_EVENT_PREFERENCES,
                            Context.MODE_PRIVATE);
                    anonymousAppDeviceGUID = preferences.getString("anonymousAppDeviceGUID", null);
                    if (anonymousAppDeviceGUID == null) {
                        // Arbitrarily prepend XZ to distinguish from device supplied identifiers.
                        anonymousAppDeviceGUID = "XZ" + UUID.randomUUID().toString();

                        context.getSharedPreferences(APP_EVENT_PREFERENCES, Context.MODE_PRIVATE)
                                .edit()
                                .putString("anonymousAppDeviceGUID", anonymousAppDeviceGUID)
                                .apply();
                    }
                }
            }
        }

        return anonymousAppDeviceGUID;
    }

    //
    // Deprecated Stuff
    //

    // Since we moved some private classes to internal classes outside the AppEventsLogger class
    // for backwards compatibility we can override the classDescriptor to resolve to the correct
    // class


    static class PersistedAppSessionInfo {
        private static final String PERSISTED_SESSION_INFO_FILENAME =
                "AppEventsLogger.persistedsessioninfo";

        private static final Object staticLock = new Object();
        private static boolean hasChanges = false;
        private static boolean isLoaded = false;
        private static Map<AccessTokenAppIdPair, FacebookTimeSpentData> appSessionInfoMap;

        private static final Runnable appSessionInfoFlushRunnable = new Runnable() {
            @Override
            public void run() {
                PersistedAppSessionInfo.saveAppSessionInformation(
                        FacebookSdk.getApplicationContext());
            }
        };

        @SuppressWarnings("unchecked")
        private static void restoreAppSessionInformation(Context context) {
            ObjectInputStream ois = null;

            synchronized (staticLock) {
                if (!isLoaded) {
                    try {
                        ois = new ObjectInputStream(
                            context.openFileInput(PERSISTED_SESSION_INFO_FILENAME));
                        appSessionInfoMap = (HashMap<AccessTokenAppIdPair, FacebookTimeSpentData>)
                                ois.readObject();
                        Logger.log(
                                LoggingBehavior.APP_EVENTS,
                                "AppEvents",
                                "App session info loaded");
                    } catch (FileNotFoundException fex) {
                    } catch (Exception e) {
                        Log.w(
                                TAG,
                                "Got unexpected exception restoring app session info: "
                                        + e.toString());
                    } finally {
                        Utility.closeQuietly(ois);
                        context.deleteFile(PERSISTED_SESSION_INFO_FILENAME);
                        if (appSessionInfoMap == null) {
                            appSessionInfoMap =
                                    new HashMap<AccessTokenAppIdPair, FacebookTimeSpentData>();
                        }
                        // Regardless of the outcome of the load, the session information cache
                        // is always deleted. Therefore, always treat the session information cache
                        // as loaded
                        isLoaded = true;
                        hasChanges = false;
                    }
                }
            }
        }

        static void saveAppSessionInformation(Context context) {
            ObjectOutputStream oos = null;

            synchronized (staticLock) {
                if (hasChanges) {
                    try {
                        oos = new ObjectOutputStream(
                                new BufferedOutputStream(
                                        context.openFileOutput(
                                                PERSISTED_SESSION_INFO_FILENAME,
                                                Context.MODE_PRIVATE)
                                )
                        );
                        oos.writeObject(appSessionInfoMap);
                        hasChanges = false;
                        Logger.log(
                                LoggingBehavior.APP_EVENTS,
                                "AppEvents",
                                "App session info saved");
                    } catch (Exception e) {
                        Log.w(
                                TAG,
                                "Got unexpected exception while writing app session info: "
                                        + e.toString());
                    } finally {
                        Utility.closeQuietly(oos);
                    }
                }
            }
        }

        static void onResume(
                Context context,
                AccessTokenAppIdPair accessTokenAppId,
                AppEventsLogger logger,
                long eventTime,
                String sourceApplicationInfo
        ) {
            synchronized (staticLock) {
                FacebookTimeSpentData timeSpentData = getTimeSpentData(context, accessTokenAppId);
                timeSpentData.onResume(logger, eventTime, sourceApplicationInfo);
                onTimeSpentDataUpdate();
            }
        }

        static void onSuspend(
                Context context,
                AccessTokenAppIdPair accessTokenAppId,
                AppEventsLogger logger,
                long eventTime
        ) {
            synchronized (staticLock) {
                FacebookTimeSpentData timeSpentData = getTimeSpentData(context, accessTokenAppId);
                timeSpentData.onSuspend(logger, eventTime);
                onTimeSpentDataUpdate();
            }
        }

        private static FacebookTimeSpentData getTimeSpentData(
                Context context,
                AccessTokenAppIdPair accessTokenAppId
        ) {
            restoreAppSessionInformation(context);
            FacebookTimeSpentData result = null;

            result = appSessionInfoMap.get(accessTokenAppId);
            if (result == null) {
                result = new FacebookTimeSpentData();
                appSessionInfoMap.put(accessTokenAppId, result);
            }

            return result;
        }

        private static void onTimeSpentDataUpdate() {
            if (!hasChanges) {
                hasChanges = true;
                backgroundExecutor.schedule(
                        appSessionInfoFlushRunnable,
                        FLUSH_APP_SESSION_INFO_IN_SECONDS,
                        TimeUnit.SECONDS);
            }
        }
    }
}
