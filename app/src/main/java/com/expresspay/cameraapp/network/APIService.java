package com.expresspay.cameraapp.network;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface APIService {

    @Multipart
    @POST("file_upload")
    Call<ResponseBody> uploadImage (@Part MultipartBody.Part file,
                                    @Part("user_id")RequestBody userId);
}
