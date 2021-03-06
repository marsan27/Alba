package com.marsanpat.alba.Controller;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import com.marsanpat.alba.Database.Message;
import com.marsanpat.alba.MainActivity;
import com.marsanpat.alba.Utils.JSONManager;
import com.marsanpat.alba.ui.logs.LogFragment;

import org.json.JSONException;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.stream.Collectors;

public class MessageController {
    private static MessageController messageController;

    private String HOSTNAME;
    private final int PORT = 5001;

    public static boolean clientActive = false;
    public static MutableLiveData<Boolean> liveClientState = new MutableLiveData<>();

    public static int PROTOCOL_STANDARD_MESSAGE_LENGTH = 1024;

    private Socket controllerSocket;

    /**
     * This queue is filled with those Strings (consisting on a header+message) which will later be adapted
     * to the AlbaProtocol and sent to the server.
     */
    private Queue<String> queueMessagesForServer;

    //SINGLETON PATTERN
    public static MessageController getInstance() {
        if (messageController==null) {
            messageController = new MessageController();
        }
        return messageController;
    }
    private MessageController(){
        liveClientState.setValue(false);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.contextOfApplication);
        HOSTNAME = prefs.getString("server_ip", "192.168.1.46");
        queueMessagesForServer = new LinkedList<String>();

    }

    public static MutableLiveData<Message> messageList = new MutableLiveData<Message>(new Message(""));

    /**
     * Starts client connection with remote server.
     * @return
     * 0 if success
     * -1 if connection was already established
     * -2 if error
     */
    public int startClient(){
        if(clientActive){
            return -1; //Client is already connected to the server, don't start another connection thread.
            //It's a crappy mutex
        }
        Thread thread = new Thread(new Runnable() {

            @Override
            public void run() {
                try (Socket socket = new Socket(HOSTNAME, PORT)) {
                    controllerSocket = socket;
                    Thread.sleep(2000);//Artificial initial delay, just for testing purposes
                    long keepAliveTimer = System.currentTimeMillis(); //TCP does not allow us to know if the server closed the connection, this emulates keep-alive functionality
                    clientActive = true; //Connection successful
                    liveClientState.postValue(true);
                    Log.d("debug","Server connection started");
                    InputStream input = socket.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(input));

                    while (clientActive) { //Reads from stream in 10sec intervals. Stops when something halts the client externally.
                        char[] response = new char[PROTOCOL_STANDARD_MESSAGE_LENGTH];
                        int charsRead = -1;
                        if(reader.ready()){
                            charsRead = reader.read(response, 0 , PROTOCOL_STANDARD_MESSAGE_LENGTH);
                        }
                        if(charsRead!=-1){
                            Log.v("debug", "Received from server: " + Arrays.toString(response));
                            Log.d("debug", "Read "+charsRead+" bytes in total");
                            //We received something from the server (might just be a PING), but enough to check connection is open
                            keepAliveTimer = System.currentTimeMillis();
                            messageList.postValue(new Message(new String(response)));
                        }else{
                            //Log.d("debug", "Connection idle");
                        }
                        sendEnqueuedMessages(socket);
                        Thread.sleep(500);

                        //Checking if the server closed the connection.
                        if(connectionTimedOut(keepAliveTimer, socket)){
                            Log.d("debug", "Connection timed out. Server closed it?");
                            clientActive = false;
                            liveClientState.postValue(false);
                        }
                    }
                    //If we reach this point something requested to stop the client. We send a final message to the server telling about it, and disconnect.
                    sendDisconnectionMessage(socket);


                } catch (UnknownHostException ex) {
                    Log.d("debug", "Server not found: " + ex.getMessage());
                } catch (IOException ex) {
                    Log.d("debug", "I/O error: " + ex.getMessage());
                }catch (Exception e) {
                    Log.d("debug", "FATAL error: " + e.getMessage());
                }finally {
                    clientActive = false; //Client stopped.
                    liveClientState.postValue(false);
                    controllerSocket = null;
                    Log.d("debug","Client stopped");
                }
            }
        });
        thread.start();
        if(clientActive){
            return 0;
        }else{
            return -2;
        }
    }

    /**
     * If the client is running, it returns the socket which is currently being used.
     * @return
     */
    public Socket getControllerSocket(){
        try{
            if(this.getLiveClientState().getValue()){
                return this.controllerSocket;
            }
        }catch(NullPointerException ex){
            Log.e("debug", "Error obtaining controller socket externally"+ ex.toString());
        }
        return null;
    }

    public void sendEnqueuedMessages(Socket socket){
        if(!this.queueMessagesForServer.isEmpty()){
            String result = this.queueMessagesForServer.remove();
            String message = new ProtocolBuilder().constructMessage(result);
            OutputStream outputStream = null;
            try {
                outputStream = socket.getOutputStream();
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
                Log.d("debug", "Sending enqueued message to the Server");
                writer.write(message, 0 , PROTOCOL_STANDARD_MESSAGE_LENGTH);
                writer.flush(); // send the message
            } catch (Exception e) {
                Log.e("debug", "Error writing in socket stream: "+e.toString());
            }
        }
    }

    //TODO: RETURN A LIST?
    public LiveData<Message> getNewMessages(){
        LiveData<Message> result = messageList;
        //TODO check this up: messageList = null;
        return result;
    }

    public void enqueueMessage(String message){
        this.queueMessagesForServer.add(message);
    }

    @Deprecated
    public boolean isClientActive(){
        return clientActive;
    }

    public LiveData<Boolean> getLiveClientState(){
        return liveClientState;
    }


    private long lastPingTimeMillis = 0;

    private boolean connectionTimedOut(long keepAliveTimer, Socket socket){
        //TODO put this in preferences/settings
        final long maxMillisWithoutNotice = 8000;
        final long delayWaitForPings = 5000;
        final long delayBetweenPings = 500;
        long currentTimeMillis = System.currentTimeMillis();
        if(currentTimeMillis-keepAliveTimer>maxMillisWithoutNotice+delayWaitForPings){
            //We gave a bit of a delay for the server to send the Pong. If it's not here yet, we just disconnect.
            sendDisconnectionMessage(socket);
            return true;
        }else if(currentTimeMillis-keepAliveTimer>maxMillisWithoutNotice){
            //We send a PING to the server. If it answers, the timer is reset.
            if(currentTimeMillis-lastPingTimeMillis>delayBetweenPings){
                lastPingTimeMillis = System.currentTimeMillis();
                sendPING(socket);
            }

            return false;
        }
        return false;
    }

    public void disconnectFromServer(){
        //Sufficient to stop our client (if it is connected to the server already).
        clientActive = false;
    }

    private void sendPING(Socket socket){
        Log.d("debug", "Sending PING to the Server");
        String toWrite = new ProtocolBuilder().constructMessage("PING::", "Hello server");
        sendMessageToServer(toWrite, socket);
    }

    private void sendPONG(Socket socket){
        Log.d("debug", "Sending PONG to the Server");
        String toWrite = new ProtocolBuilder().constructMessage("PONG::", "Hello server");
        sendMessageToServer(toWrite, socket);
    }

    private void sendDisconnectionMessage(Socket socket){
        Log.d("debug", "Sending DISCONN to the Server");
        String toWrite = new ProtocolBuilder().constructMessage("DISCONN::", "");
        sendMessageToServer(toWrite, socket);

    }

    public void testRequestLastDBline(){
        Socket sock = this.getControllerSocket();
        if(sock!=null){
            String message = new ProtocolBuilder().constructMessage("REQUEST::", "testlastrowdb");
            enqueueMessage(message);
        }else{
            Log.e("debug", "Requested to send last db line, but socket is closed");
        }
    }

    private void sendMessageToServer(String message, Socket socket){
        OutputStream outputStream = null;
        try {
            outputStream = socket.getOutputStream();
            // create a data output stream from the output stream so we can send data through it
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
            writer.write(message, 0 , PROTOCOL_STANDARD_MESSAGE_LENGTH);
            writer.flush(); // send the message
            Log.d("debug", "Message sent");
        } catch (Exception e) {
            Log.e("debug", "Error writing in socket stream: "+e.toString());
        }
    }


}
