/*
 * Copyright 2016 Saikrishna Arcot <saiarcot895@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License or (at your option) version 3 or any later version
 * accepted by the membership of KDE e.V. (or its successor approved
 * by the membership of KDE e.V.), which shall act as a proxy
 * defined in Section 14 of version 3 of the license.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
*/

package org.kde.kdeconnect.Backends.BluetoothBackend;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.kde.kdeconnect.Backends.DeviceLink;
import org.kde.kdeconnect.Backends.BasePairingHandler;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPacket;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.UUID;

import androidx.annotation.WorkerThread;

public class BluetoothLink extends DeviceLink {
    private final ConnectionMultiplexer connection;
    private final InputStream input;
    private final OutputStream output;
    private final BluetoothDevice remoteAddress;
    private final BluetoothLinkProvider linkProvider;

    private boolean continueAccepting = true;

    private final Thread receivingThread = new Thread(new Runnable() {
        @Override
        public void run() {
            StringBuilder sb = new StringBuilder();
            try {
                Reader reader = new InputStreamReader(input, "UTF-8");
                char[] buf = new char[512];
                while (continueAccepting) {
                    while (sb.indexOf("\n") == -1 && continueAccepting) {
                        int charsRead;
                        if ((charsRead = reader.read(buf)) > 0) {
                            sb.append(buf, 0, charsRead);
                        }
                        if (charsRead < 0) {
                            disconnect();
                            return;
                        }
                    }
                    if (!continueAccepting) break;

                    int endIndex = sb.indexOf("\n");
                    if (endIndex != -1) {
                        String message = sb.substring(0, endIndex + 1);
                        sb.delete(0, endIndex + 1);
                        processMessage(message);
                    }
                }
            } catch (IOException e) {
                Log.e("BluetoothLink/receiving", "Connection to " + remoteAddress.getAddress() + " likely broken.", e);
                disconnect();
            }
        }

        private void processMessage(String message) {
            NetworkPacket np;
            try {
                np = NetworkPacket.unserialize(message);
            } catch (JSONException e) {
                Log.e("BluetoothLink/receiving", "Unable to parse message.", e);
                return;
            }

            if (np.hasPayloadTransferInfo()) {
                try {
                    UUID transferUuid = UUID.fromString(np.getPayloadTransferInfo().getString("uuid"));
                    InputStream payloadInputStream = connection.getChannelInputStream(transferUuid);
                    np.setPayload(new NetworkPacket.Payload(payloadInputStream, np.getPayloadSize()));
                } catch (Exception e) {
                    Log.e("BluetoothLink/receiving", "Unable to get payload", e);
                }
            }

            packageReceived(np);
        }
    });

    public BluetoothLink(Context context, ConnectionMultiplexer connection, InputStream input, OutputStream output, BluetoothDevice remoteAddress, String deviceId, BluetoothLinkProvider linkProvider) {
        super(context, deviceId, linkProvider);
        this.connection = connection;
        this.input = input;
        this.output = output;
        this.remoteAddress = remoteAddress;
        this.linkProvider = linkProvider;
    }

    public void startListening() {
        this.receivingThread.start();
    }

    @Override
    public String getName() {
        return "BluetoothLink";
    }

    @Override
    public BasePairingHandler getPairingHandler(Device device, BasePairingHandler.PairingHandlerCallback callback) {
        return new BluetoothPairingHandler(device, callback);
    }

    public void disconnect() {
        if (connection == null) {
            return;
        }
        continueAccepting = false;
        try {
            connection.close();
        } catch (IOException ignored) {
        }
        linkProvider.disconnectedLink(this, getDeviceId(), remoteAddress);
    }

    private void sendMessage(NetworkPacket np) throws JSONException, IOException {
        byte[] message = np.serialize().getBytes(Charset.forName("UTF-8"));
        Log.i("BluetoothLink", "Beginning to send message");
        output.write(message);
        Log.i("BluetoothLink", "Finished sending message");
    }

    @WorkerThread
    @Override
    public boolean sendPacket(NetworkPacket np, final Device.SendPacketStatusCallback callback) {

        /*if (!isConnected()) {
            Log.e("BluetoothLink", "sendPacketEncrypted failed: not connected");
            callback.sendFailure(new Exception("Not connected"));
            return;
        }*/

        try {
            UUID transferUuid = null;
            if (np.hasPayload()) {
                transferUuid = connection.newChannel();
                JSONObject payloadTransferInfo = new JSONObject();
                payloadTransferInfo.put("uuid", transferUuid.toString());
                np.setPayloadTransferInfo(payloadTransferInfo);
            }

            sendMessage(np);

            if (transferUuid != null) {
                try (OutputStream payloadStream = connection.getChannelOutputStream(transferUuid)) {
                    int BUFFER_LENGTH = 1024;
                    byte[] buffer = new byte[BUFFER_LENGTH];

                    int bytesRead;
                    long progress = 0;
                    InputStream stream = np.getPayload().getInputStream();
                    while ((bytesRead = stream.read(buffer)) != -1) {
                        progress += bytesRead;
                        payloadStream.write(buffer, 0, bytesRead);
                        if (np.getPayloadSize() > 0) {
                            callback.onProgressChanged((int) (100 * progress / np.getPayloadSize()));
                        }
                    }
                    payloadStream.flush();
                } catch (Exception e) {
                    callback.onFailure(e);
                    return false;
                }
            }

            callback.onSuccess();
            return true;
        } catch (Exception e) {
            callback.onFailure(e);
            return false;
        }
    }


    /*
    public boolean isConnected() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
            return socket.isConnected();
        } else {
            return true;
        }
    }
*/
}
