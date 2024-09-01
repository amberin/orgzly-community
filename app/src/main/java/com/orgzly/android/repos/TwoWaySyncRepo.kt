package com.orgzly.android.repos

import android.net.Uri
import java.io.File
import java.io.IOException

interface TwoWaySyncRepo {

    fun getUri(): Uri
}