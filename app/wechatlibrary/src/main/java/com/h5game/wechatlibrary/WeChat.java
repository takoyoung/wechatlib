package com.h5game.wechatlibrary;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.h5game.thirdpartycallback.ThirdPartyCallback;
import com.tencent.mm.opensdk.constants.ConstantsAPI;
import com.tencent.mm.opensdk.modelbase.BaseReq;
import com.tencent.mm.opensdk.modelbase.BaseResp;
import com.tencent.mm.opensdk.modelbiz.WXLaunchMiniProgram;
import com.tencent.mm.opensdk.modelmsg.SendAuth;
import com.tencent.mm.opensdk.modelmsg.SendMessageToWX;
import com.tencent.mm.opensdk.modelmsg.WXImageObject;
import com.tencent.mm.opensdk.modelmsg.WXMediaMessage;
import com.tencent.mm.opensdk.modelmsg.WXTextObject;
import com.tencent.mm.opensdk.modelmsg.WXWebpageObject;
import com.tencent.mm.opensdk.modelpay.PayReq;
import com.tencent.mm.opensdk.openapi.IWXAPI;
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler;
import com.tencent.mm.opensdk.openapi.WXAPIFactory;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

public class WeChat extends ThirdPartyCallback implements IWXAPIEventHandler {
    private IWXAPI wxapi;
    private boolean m_bLogin = false;

    public WeChat(Context context, Activity activity, String appID, String className){
        super(className);
        wxapi = WXAPIFactory.createWXAPI(context, appID, true);
        wxapi.registerApp(appID);
        mActivity = activity;

        mActivity.registerReceiver(new BroadcastReceiver(){
            @Override
            public void onReceive(Context context, Intent intent) {
                wxapi.registerApp(appID);
            }
        }, new IntentFilter(ConstantsAPI.ACTION_REFRESH_WXAPP));

    }

    public void handleIntent(Intent intent) {
        wxapi.handleIntent(intent, this);
    }

    @Override
    public void onReq(BaseReq arg) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onResp(BaseResp resp) {
        Map<String, Object> retAgv = new HashMap<>();
        if (resp.getType() == ConstantsAPI.COMMAND_LAUNCH_WX_MINIPROGRAM) {
            WXLaunchMiniProgram.Resp launchMiniProResp = (WXLaunchMiniProgram.Resp) resp;
            String extraData =launchMiniProResp.extMsg; //对应小程序组件 <button open-type="launchApp"> 中的 app-parameter 属性
            log("miniprogram is back : "+extraData);
        }else {
            switch (resp.errCode) {
                case BaseResp.ErrCode.ERR_OK:
                    if (m_bLogin) {
                        SendAuth.Resp auth_resp = (SendAuth.Resp) resp;
                        retAgv.put("code", auth_resp.code);
                        m_bLogin = false;
                    }
                    retAgv.put("bundleid", mActivity.getPackageName());
                    callSuccess(retAgv);
                    break;
                case BaseResp.ErrCode.ERR_COMM:
                    callErr(-1, 3, "操作发生异常！");
                    break;
                case BaseResp.ErrCode.ERR_USER_CANCEL:
                    callErr(-1, 4, "用户取消操作！");
                    break;
                case BaseResp.ErrCode.ERR_AUTH_DENIED:
                    callErr(-1, 5, "用户拒绝授权！");
                    break;
                default:
                    callErr(-1, 6, resp.errStr);
                    break;
            }
        }
    }

    public void onWeChatEvent(String eveName, int callbackId, Map<String,Object> args){
        if(!wxapi.isWXAppInstalled())
        {
            callErr(callbackId, 2, "请先安装微信！");
            return;
        }

        if(!checkCallbackId(callbackId)){
            return;
        }

        log("start "+eveName);
        if(eveName.equals("Login")){
            Login(callbackId, args);
        }else if(eveName.equals("Pay")){
            Pay(callbackId, args);
        }else if(eveName.equals("Share")){
            Share(callbackId, args);
        }else if(eveName.equals("OpenMiniProgram")){
            OpenMiniProgram(callbackId, args);
        }
    }

    private int getMsgScene(String scene) {
        if(scene==null)
            return SendMessageToWX.Req.WXSceneSession;

        if(scene.equals("session"))
            return SendMessageToWX.Req.WXSceneSession;

        if(scene.equals("timeline"))
            return SendMessageToWX.Req.WXSceneTimeline;

        if(scene.equals("favorite"))
            return SendMessageToWX.Req.WXSceneFavorite;

        return SendMessageToWX.Req.WXSceneSession;
    }

    private void Login(int callbackId, Map<String,Object> args){
        m_bLogin = true;

        final SendAuth.Req req = new SendAuth.Req();
        req.scope = "snsapi_userinfo";
        req.state = String.valueOf(getCallbackId());
        wxapi.sendReq(req);
        log("open wechat client");
    }

    private void Pay(int callbackId, Map<String,Object> args){
        Map<String,Object> payJson = null;
        payJson = (Map)args.get("pay");

        if(payJson == null){
            callErr(-1, 7, "参数不全！");
            return;
        }

        PayReq request = new PayReq();
        request.appId = (String)payJson.get("appid");
        request.partnerId = (String)payJson.get("partnerid");
        request.prepayId = (String)payJson.get("prepayid");
        request.packageValue = (String)payJson.get("package");
        request.nonceStr = (String)payJson.get("noncestr");
        request.timeStamp = String.valueOf((Integer)payJson.get("timestamp"));
        request.sign = (String)payJson.get("sign");
        wxapi.sendReq(request);
    }

    private void Share(int callbackId, Map<String,Object> args){
        String type = (String)args.get("type");

        if(type == null){
            callErr(-1, 7, "参数不全！");
            return;
        }

        if(type.equals("text")){
            ShareText(callbackId, args);
        }else if(type.equals("url")){
            ShareUrl(callbackId, args);
        }else if(type.equals("image")){
            ShareImage(callbackId, args);
        }
    }

    private String buildTransaction(final String type) {
        return (type == null) ? String.valueOf(System.currentTimeMillis()) : type + System.currentTimeMillis();
    }

    private void ShareText(int callbackId, Map<String,Object> agv){
        String scene = (String) agv.get("scene");
        String text = (String) agv.get("text");

        WXTextObject textObj = new WXTextObject();
        textObj.text = text;

        WXMediaMessage msg = new WXMediaMessage(textObj);
        msg.mediaObject = textObj;
        msg.description = text;

        SendMessageToWX.Req req = new SendMessageToWX.Req();
        req.transaction = buildTransaction("text");
        req.scene = getMsgScene(scene);
        req.message = msg;

        wxapi.sendReq(req);
    }

    private void ShareUrl(int callbackId, Map<String,Object> agv){
        String scene = (String) agv.get("scene");
        String url = (String) agv.get("url");
        String title = (String) agv.get("title");
        String desc = (String) agv.get("desc");
        String img = (String)agv.get("img");

        if(url==null || title==null || desc==null || img==null )
        {
            callErr(-1, 7, "参数不全！");
            return;
        }

        Bitmap thumb = BitmapFactory.decodeResource(mActivity.getResources(), mActivity.getResources().getIdentifier("ic_launcher", "mipmap", mActivity.getPackageName()));
        if(thumb==null)
        {
            callErr(-1, 8, "缩略图不存在");
            return;
        }

        WXWebpageObject webpage = new WXWebpageObject();
        webpage.webpageUrl = url;

        WXMediaMessage msg = new WXMediaMessage(webpage);
        msg.title = title;
        msg.description = desc;
        msg.thumbData = bmpToByteArray(thumb, true);

        SendMessageToWX.Req req = new SendMessageToWX.Req();
        req.transaction = buildTransaction("webpage");
        req.scene = getMsgScene(scene);
        req.message = msg;

        wxapi.sendReq(req);
    }

    private void ShareImage(int callbackId, Map<String,Object> agv){
        String scene = (String) agv.get("scene");
        String base64Data = (String)agv.get("base64Data");
        Integer thumb_scale = (Integer)agv.get("thumb_scale");
        Bitmap bitmap = null;
        if(base64Data==null)
        {
            callErr(-1, 7, "参数不全！");
            return;
        }

        if(base64Data!=null){
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        }

        if(bitmap==null) {
            callErr(-1, 8, "缩略图不存在");
            return;
        }

        if(thumb_scale==null || thumb_scale<=0)
            thumb_scale = 10;

        WXMediaMessage msg = new WXMediaMessage();
        WXImageObject imgObj = new WXImageObject(bitmap);
        msg.mediaObject = imgObj;

        //thumb_scale

        Bitmap thumbBmp = Bitmap.createScaledBitmap(bitmap, bitmap.getWidth()*thumb_scale/100, bitmap.getHeight()*thumb_scale/100, true);
        bitmap.recycle();
        msg.thumbData = bmpToByteArray(thumbBmp, true);

        SendMessageToWX.Req req = new SendMessageToWX.Req();
        req.transaction = buildTransaction("img");
        req.scene = getMsgScene(scene);
        req.message = msg;

        wxapi.sendReq(req);
    }

    private void OpenMiniProgram(int callbackId, Map<String,Object> agv){
        String miniId = (String)agv.get("miniId");
        String miniPath = (String)agv.get("miniPath");
        int miniType = (int)agv.get("miniType");

        WXLaunchMiniProgram.Req req = new WXLaunchMiniProgram.Req();
        req.userName = miniId; // 填小程序原始id
        req.path = miniPath; //拉起小程序页面的可带参路径，不填默认拉起小程序首页
        req.miniprogramType = miniType;//可选打开 开发版，体验版和正式版
        wxapi.sendReq(req);
    }

    private byte[] bmpToByteArray(final Bitmap bmp, final boolean needRecycle) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, output);
        if (needRecycle) {
            bmp.recycle();
        }

        byte[] result = output.toByteArray();
        try {
            output.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;
    }
}
