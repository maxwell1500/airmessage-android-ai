package me.tagavari.airmessage.activity

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.mikepenz.aboutlibraries.LibsBuilder
import me.tagavari.airmessage.R
import me.tagavari.airmessage.composite.AppCompatCompositeActivity
import me.tagavari.airmessage.compositeplugin.PluginQNavigation

class Licenses : AppCompatCompositeActivity() {
    var fragment: Fragment? = null

    init {
        addPlugin(PluginQNavigation())
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_licenses)

        if (savedInstanceState == null) {
            //Initializing the fragment
            fragment = LibsBuilder()
                .withAboutMinimalDesign(true)
                .withVersionShown(false)
                .withAboutIconShown(false)
                .withEdgeToEdge(true)
                .supportFragment()
            getSupportFragmentManager().beginTransaction().add(R.id.container, fragment!!).commit()
        } else {
            fragment = getSupportFragmentManager().getFragment(savedInstanceState, keyFragment)
        }

        //Enabling up navigation
        setSupportActionBar(findViewById<Toolbar?>(R.id.toolbar))
        getSupportActionBar()!!.setTitle(R.string.screen_licenses)
        getSupportActionBar()!!.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        //Saving the fragment
        if (fragment != null && fragment!!.isAdded()) getSupportFragmentManager().putFragment(
            outState,
            keyFragment,
            fragment!!
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        //Up button
        if (item.getItemId() == android.R.id.home) {
            //Finishing the activity
            finish()
            return true
        }

        return false
    }

    companion object {
        private const val keyFragment = "fragment"
    }
}