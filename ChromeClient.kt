class ChromeClient(private val context: Context, private val progressbar: ProgressBar, private val messageCallback: MessageCallback): WebChromeClient() {

    private var chromeView: View? = null
    private var fullScreenContainer: FullScreenHolder? = null
    private var customViewCallback: CustomViewCallback? = null
    private var originalOrientation: Int = (context as Activity).requestedOrientation

    companion object {
        private val COVER_SCREEN_PARAMS = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        progressbar.visibility = View.VISIBLE;

        if(newProgress >= 100){
            progressbar.visibility = View.GONE;
        }
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        Log.i("JS Console.log", "${consoleMessage?.message()} , ${consoleMessage?.messageLevel()} , ${consoleMessage?.sourceId()}")
        return super.onConsoleMessage(consoleMessage)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if(chromeView != null) {
            callback?.onCustomViewHidden()
            return
        }

        val decor = (context as Activity).window.decorView as FrameLayout
        fullScreenContainer = FullScreenHolder(context)
        fullScreenContainer?.addView(view, COVER_SCREEN_PARAMS)
        decor.addView(fullScreenContainer, COVER_SCREEN_PARAMS)
        chromeView = view
        setFullScreen(true)
        customViewCallback = callback

        super.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        if(chromeView == null) {
            return;
        }

        setFullScreen(false)
        val decor = (context as Activity).window.decorView as FrameLayout
        decor.removeView(fullScreenContainer)
        fullScreenContainer = null
        chromeView = null
        customViewCallback?.onCustomViewHidden()
        (context as Activity).requestedOrientation = originalOrientation

    }

    private fun setFullScreen(enable: Boolean) {
        val window = (context as Activity).window
        val winParams = window.attributes
        val bit = WindowManager.LayoutParams.FLAG_FULLSCREEN
        if(enable) {
            winParams.flags = winParams.flags or bit
        } else {
            winParams.flags = winParams.flags and bit.inv()
            if (chromeView != null) {
                chromeView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    override fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        AlertDialog.Builder(context)
            .setTitle("")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { dialog, which -> result?.confirm() }
            .setNegativeButton(android.R.string.cancel) { dialog, which -> result?.cancel()  }
            .create()
            .show()
        return true
    }

    override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        AlertDialog.Builder(context)
            .setTitle("")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { dialog, which -> result?.confirm() }
            .create()
            .show()
        return true
    }

    class FullScreenHolder(context: Context) : FrameLayout(context) {
        init {
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.white))
        }

        override fun onTouchEvent(event: MotionEvent?): Boolean {
            return true
        }
    }


    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        this.filePathCallback = filePathCallback
        if(PermissionCheck(context).storage()) {
            ActivityCompat.requestPermissions(context as Activity, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), RequestValue.PERMISSION_OK)
        } else {
            takePhoto()
        }
        return true
    }

    fun chromePermissionResult(requestCode: Int,
                                  permissions: Array<out String>,
                                  grantResults: IntArray) {
        when(requestCode) {
            RequestValue.PERMISSION_OK -> {
                if(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    takePhoto()
                }else {
                    messageCallback.msg(context.getString(R.string.required_permission))
                }
            }
        }
    }

    fun chromeActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(resultCode != Activity.RESULT_OK) {
            filePathCallback?.onReceiveValue(null)
            return
        }

        when(requestCode) {
            RequestValue.TAKE_PHOTO -> {
                try {
                    if(data?.data != null) {
                        Log.i("file data", data.data!!.path)
                        //filePathCallback?.onReceiveValue(arrayOf(data.data!!))
                        var absolutePath = absolutelyPath(data.data!!)
                        var exif = ExifInterface(absolutePath)
                        var image : Bitmap = BitmapFactory.decodeFile(absolutePath)
                        var exifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                        var exifDegree : Float = exifOrientationTODegrees(exifOrientation)
                        image = rotate(image, exifDegree)
                        var result : Uri = getImageUri(image)

                        filePathCallback?.onReceiveValue(arrayOf(result))
                    } else {
                        messageCallback.msg(context.getString(R.string.upload_fail))
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = null
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    filePathCallback = null
                }
            }
        }
    }

    // 사진요청
    private fun takePhoto() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = android.provider.MediaStore.Images.Media.CONTENT_TYPE
        (context as Activity).startActivityForResult(intent, RequestValue.TAKE_PHOTO)
    }

    private fun exifOrientationTODegrees(exifOrientation: Int) : Float {
        when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> {
                return 90f
            }
            ExifInterface.ORIENTATION_ROTATE_180 -> {
                return 180f
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> {
                return 270f
            }
            else -> {
                return 0f
            }
        }
    }

    private fun rotate(bitmap: Bitmap, degree:Float) : Bitmap {
        var width = bitmap.width
        var height = bitmap.height

        var mtx: Matrix = Matrix()
        mtx.setRotate(degree)

        return Bitmap.createBitmap(bitmap, 0, 0, width, height, mtx, true)
    }

    private fun getImageUri(inImage: Bitmap): Uri {
        val bytes = ByteArrayOutputStream()
        inImage.compress(Bitmap.CompressFormat.JPEG, 100, bytes)
        val path: String = MediaStore.Images.Media.insertImage(
            context.contentResolver,
            inImage,
            "Title",
            null
        )
        return Uri.parse(path)
    }

    // 절대경로 변환
    private fun absolutelyPath(path: Uri): String? {
        var proj: Array<String> = arrayOf(MediaStore.Images.Media.DATA)
        var c: Cursor? = context.contentResolver.query(path, proj, null, null, null)
        var index = c?.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
        c?.moveToFirst()

        return index?.let { c?.getString(it) }
    }

}
