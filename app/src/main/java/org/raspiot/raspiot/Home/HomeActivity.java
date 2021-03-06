package org.raspiot.raspiot.Home;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.os.Handler;
import android.os.Message;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.litepal.crud.DataSupport;
import org.raspiot.raspiot.Auth.LogInActivity;
import org.raspiot.raspiot.R;
import org.raspiot.raspiot.Room.json.RoomJSON;
import org.raspiot.raspiot.Settings.SettingsActivity;
import org.raspiot.raspiot.Home.list.Room;
import org.raspiot.raspiot.Home.list.RoomAdapter;
import org.raspiot.raspiot.DatabaseGlobal.UserInfoDB;
import org.raspiot.raspiot.JsonGlobal.ControlMessage;
import org.raspiot.raspiot.NetworkGlobal.HttpUtil;
import org.raspiot.raspiot.NetworkGlobal.TCPClient;
import org.raspiot.raspiot.NetworkGlobal.ThreadCallbackListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Response;

import static org.raspiot.raspiot.Auth.LocalValidation.isRaspiotCloudMode;
import static org.raspiot.raspiot.Home.RoomDatabaseHandler.getAllRoomDataFromDatabase;
import static org.raspiot.raspiot.Home.RoomDatabaseHandler.getRestRoomList;
import static org.raspiot.raspiot.Home.RoomDatabaseHandler.parseRoomDataAndSaveToDatabase;
import static org.raspiot.raspiot.Home.HomeJSONHandler.parseRoomJSONListWithGSON;
import static org.raspiot.raspiot.Home.list.RoomListHandler.updateRoomListAndNotifyItem;
import static org.raspiot.raspiot.UICommonOperations.ReminderShow.ToastShowInBottom;
import static org.raspiot.raspiot.DatabaseGlobal.DatabaseCommonOperations.CLOUD_SERVER_ID;
import static org.raspiot.raspiot.DatabaseGlobal.DatabaseCommonOperations.CurrentHostModeIsCloudServerMode;
import static org.raspiot.raspiot.DatabaseGlobal.DatabaseCommonOperations.DEFAULT_USER_INFO_ID;
import static org.raspiot.raspiot.DatabaseGlobal.DatabaseCommonOperations.RASP_SERVER_ID;
import static org.raspiot.raspiot.DatabaseGlobal.DatabaseCommonOperations.getCurrentUserInfo;
import static org.raspiot.raspiot.DatabaseGlobal.DatabaseCommonOperations.getHostAddrFromDatabase;
import static org.raspiot.raspiot.JsonGlobal.JsonCommonOperations.buildJSON;


public class HomeActivity extends AppCompatActivity {

    private long exitTime = 0;
    private UserInfoDB userInfo;
    private String dataFromNetworkResponse;
    private DrawerLayout mDrawerLayout;
    private TextView connectHint;
    private List<Room> roomList = new ArrayList<>();
    private RoomAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;

    private static final int NETWORK_ERROR = -1;
    private static final int GET_ROOM_LIST_SUCCEED = 1;
    private static final int CMD_ERROR = 2;
    private static final int HOST_CONFIRM = 3;

    public static final String ROOM_NAME = "room_name";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        initUIWidget();
    }

    //Toolbar右上角菜单
    @Override
    public boolean onCreateOptionsMenu(Menu menu){
        getMenuInflater().inflate(R.menu.home_toolbar_menu,menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item){
        switch(item.getItemId()){
            /*左上角HomeAsUp响应*/
            case android.R.id.home:
                mDrawerLayout.openDrawer(GravityCompat.START);
                break;
            default:
        }
        return true;
    }


    //异步消息处理
    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            connectHint.setVisibility(View.GONE);
            switch (msg.what){
                case GET_ROOM_LIST_SUCCEED:   //FROM SERVER
                    /* 获取消息 更新UI */
                    /* Change UI here */

                    /* Parse RoomJSON data from Network*/
                    List<RoomJSON> roomJSONList = parseRoomJSONListWithGSON(dataFromNetworkResponse);
                    /* Save data which parsed by Gson to database */
                    parseRoomDataAndSaveToDatabase(roomJSONList);
                    updateRoomListAndNotifyItem(getAllRoomDataFromDatabase(), roomList, adapter);
                    swipeRefresh.setRefreshing(false);
                    ToastShowInBottom("Refresh finish.");
                    break;


                case CMD_ERROR:
                    ToastShowInBottom(dataFromNetworkResponse);
                    break;

                case NETWORK_ERROR:
                    swipeRefresh.setRefreshing(false);
                    /* Try to check services with server every 5 seconds if connect server error */
                    connectHint.setVisibility(View.VISIBLE);
                    checkServicesWithNetwork(5000);
                    break;

                default:
                    break;
            }
        }
    };



    private void refreshRooms(){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try{
                    Thread.sleep(100);
                }catch (InterruptedException e){
                    e.printStackTrace();
                }
                getRoomListWithNetwork();
            }
        }).start();
    }


    private void getRoomListWithNetwork(){
        ControlMessage getRoomList = new ControlMessage("get", "room", "roomlist");
        String getRoomListJson = buildJSON(getRoomList);
        if(CurrentHostModeIsCloudServerMode())
            sendRequestWithOkHttp(getRoomListJson);
        else {
            sendRequestWithSocket(getRoomListJson);
        }
    }

    private void checkServicesWithNetwork(final int timeout){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try{
                    Thread.sleep(timeout);
                }catch (InterruptedException e){
                    e.printStackTrace();
                }
                ControlMessage checkServices = new ControlMessage("get", "server", "checkServices");
                String checkServicesJson = buildJSON(checkServices);
                if(CurrentHostModeIsCloudServerMode()) {
                    sendRequestWithOkHttp(checkServicesJson);
                }else{
                    sendRequestWithSocket(checkServicesJson);
                }
            }
        }).start();
    }
    /*********************Initialize  start*****************************/

    private void initUIWidget(){
        /*Toolbar*/
        initToolbar();
        initNavigationView();

        /*Main list*/
        initRooms();//初始化房间数据
        initRecyclerView();

        initConnectHint();
    }

    private void initConnectHint(){
        connectHint = (TextView) findViewById(R.id.home_connect_hint);
        checkServicesWithNetwork(100);
    }

    private void initToolbar(){
        Toolbar toolBar = (Toolbar) findViewById(R.id.home_toolbar);
        toolBar.setTitle("Smart house");
        toolBar.setSubtitle("raspiot is fantastic");
        toolBar.setNavigationIcon(R.drawable.toolbar_icon_raspiot);
        setSupportActionBar(toolBar);

        toolBar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
               @Override
               public boolean onMenuItemClick(MenuItem item) {
                   switch (item.getItemId()) {
                       //右上角Add Room响应
                       case R.id.add_room:
                           toolbarAddNewRoom();
                           break;
                       default:
                   }
                   return true;
               }
           }
        );
    }

    private void initRecyclerView(){
        RecyclerView recyclerView = (RecyclerView) findViewById(R.id.rooms_recycler_view);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        adapter = new RoomAdapter(roomList);
        recyclerView.setAdapter(adapter);


        /*Let the RecyclerView supported SwipeRefresh*/
        swipeRefresh = (SwipeRefreshLayout) findViewById(R.id.rooms_swipe_refresh);
        swipeRefresh.setColorSchemeResources(R.color.colorPrimary);
        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener(){
            @Override
            public void onRefresh(){
                refreshRooms();
            }
        });
    }

    private void initNavigationView(){
        userInfo = getCurrentUserInfo();
        //左侧DrawerLayout
        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        NavigationView navView =(NavigationView)findViewById(R.id.nav_view);
        navView.setItemIconTintList(null);  //菜单图标颜色

        /*
        ImageView navAvatar = (ImageView) navView.getHeaderView(R.id.nav_avatar);
        TextView navUsername = (TextView) navView.getHeaderView(R.id.nav_username);
        navUsername.setText(userInfo.getUsername());
        */

        //navigationView 消息提醒 添加角标
        LinearLayout navMsg = (LinearLayout) navView.getMenu().findItem(R.id.nav_msg).getActionView();
        final TextView msg= (TextView) navMsg.findViewById(R.id.nav_msg_remind);
        //动态修改 TextView 背景图
        msg.setBackgroundResource(R.drawable.home_nav_icon_msg_remind);
        msg.setText("9");

        //navigationView 动态设置 条目 小标题
        LinearLayout navEmail = (LinearLayout) navView.getMenu().findItem(R.id.nav_email).getActionView();
        TextView email= (TextView) navEmail.findViewById(R.id.nav_email_text);
        String userEmail = userInfo.getEmail();
        email.setText(userEmail);

        //左侧NavigationView 菜单
        navView.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener(){
            @Override
            public boolean onNavigationItemSelected(MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.nav_msg:
                        msg.setBackgroundResource(R.drawable.home_nav_icon_msg_none);
                        msg.setText("");
                        break;

                    case R.id.nav_settings:
                        mDrawerLayout.closeDrawers();
                        Intent intent_settings= new Intent(HomeActivity.this, SettingsActivity.class);
                        startActivity(intent_settings);
                        break;

                    case R.id.nav_log_out:
                        if(userInfo.getId() != DEFAULT_USER_INFO_ID && isRaspiotCloudMode()) {
                            DataSupport.delete(UserInfoDB.class, userInfo.getId());
                            Intent intent_login = new Intent(HomeActivity.this, LogInActivity.class);
                            startActivity(intent_login);
                        }
                        break;
                    default:
                }
                return true;
            }
        });
    }

    private void initRooms(){
        List<String> roomNameList = getRestRoomList();
        for(String roomName : roomNameList){
            Room room = new Room(roomName, R.drawable.recyclerview_item_image);
            roomList.add(room);
        }
    }


    private void toolbarAddNewRoom(){
        AlertDialog.Builder addRoomDialog = new AlertDialog.Builder(HomeActivity.this);

        final EditText newRoomName = new EditText(HomeActivity.this);
        newRoomName.setHint("Input a new room name");
        newRoomName.setMaxLines(1);
        newRoomName.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                                                  @Override
                                                  public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                                                      if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                                                          return true;  // if input Enter, put away
                                                      }
                                                      return false;
                                                  }
                                              });
        addRoomDialog.setCancelable(false);
        addRoomDialog.setTitle("Add a new room");
        addRoomDialog.setView(newRoomName);
        addRoomDialog.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String newName = newRoomName.getText().toString();
                        if (newName.isEmpty())
                            ToastShowInBottom("Room name can't be empty.");
                        else if (getRestRoomList().contains(newName))
                            ToastShowInBottom(newName + " is already exists.");
                        else{
                            ControlMessage addNewRoomCmd = new ControlMessage("add", "room", newRoomName.getText().toString());
                            sendRequestWithSocket(buildJSON(addNewRoomCmd));
                        }
                    }
                });
        addRoomDialog.setNegativeButton("Cancel", null);
        addRoomDialog.show();
    }
    /*********************Initialize end*****************************/



    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN){
            if(mDrawerLayout.isDrawerOpen(GravityCompat.START)){
                mDrawerLayout.closeDrawers();
            }else if((System.currentTimeMillis()-exitTime) > 2000){
                Toast.makeText(getApplicationContext(), "再按一次退出程序", Toast.LENGTH_SHORT).show();
                exitTime = System.currentTimeMillis();
            } else {
                finish();
                System.exit(0);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }


    private void sendRequestWithSocket(String data){
        String addr = getHostAddrFromDatabase(RASP_SERVER_ID);
        String ip = addr.split(":")[0];
        int port = Integer.parseInt(addr.split(":")[1]);
        TCPClient.tcpClient(ip, port, data, new ThreadCallbackListener() {
            @Override
            public void onFinish(String response) {
                onNetworkResponse(response);
            }
            @Override
            public void onError(Exception e) {
                onNetworkError();
                e.printStackTrace();
            }
        });
    }


    private void sendRequestWithOkHttp(String data){
        String addr = getHostAddrFromDatabase(CLOUD_SERVER_ID)+"/api/relay";
        HttpUtil.sendOkHttpRequest(addr, data, new okhttp3.Callback(){
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                onNetworkResponse(response.body().string());
            }
            @Override
            public void onFailure(Call call, IOException e){
                onNetworkError();
                e.printStackTrace();
            }
        });
    }

    private void onNetworkResponse(String response){
        Message message = new Message();
        if(!response.equals("")) {
            dataFromNetworkResponse = response;
            if(response.length() > 40)
                message.what = GET_ROOM_LIST_SUCCEED;
            else if(response.equals("raspServer is ready."))
                message.what = HOST_CONFIRM;
            else if(response.equals("raspServer is offline."))
                message.what = HOST_CONFIRM;
            else if(response.equals("You need to log in."))
                message.what = HOST_CONFIRM;
            else {
                message.what = CMD_ERROR;
            }
            handler.sendMessage(message);
        }else{
            onNetworkError();
        }
    }

    private void onNetworkError(){
        Message message = new Message();
        message.what = NETWORK_ERROR;
        handler.sendMessage(message);
    }
}
