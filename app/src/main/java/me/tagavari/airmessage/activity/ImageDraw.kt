package me.tagavari.airmessage.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import me.tagavari.airmessage.R

class ImageDraw : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        //Calling the super method
        super.onCreate(savedInstanceState)

        //Setting the content view
        setContentView(R.layout.activity_imagedraw)

        //((ImageView) findViewById(R.id.imagedraw_mainimage)).setImageURI();
    }
}