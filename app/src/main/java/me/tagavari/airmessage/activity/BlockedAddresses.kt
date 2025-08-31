package me.tagavari.airmessage.activity

import android.content.Context
import android.os.AsyncTask
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.tagavari.airmessage.R

import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.Iterator

class BlockedAddresses : AppCompatActivity() {
    //Creating the view values
    private lateinit var blockedListAdapter: ListAdapter

    //Creating the retained fragment values
    private lateinit var retainedFragment: RetainedFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        //Calling the super method
        super.onCreate(savedInstanceState)

        //Setting the content view
        setContentView(R.layout.activity_blockedaddresses)

        //Preparing the retained fragment
        prepareRetainedFragment()

        //Setting up the views
        blockedListAdapter = ListAdapter(this, R.layout.activity_blockedaddresses_listitem, retainedFragment.blockedList)
        (findViewById<View>(R.id.list) as ListView).adapter = blockedListAdapter
    }

    private fun prepareRetainedFragment() {
        //Getting the retained fragment
        val fragmentManager: FragmentManager = supportFragmentManager
        retainedFragment = fragmentManager.findFragmentByTag(retainedFragmentTag) as RetainedFragment?
            ?: run {
                val newFragment = RetainedFragment()
                fragmentManager.beginTransaction().add(newFragment, retainedFragmentTag).commit()
                newFragment
            }
    }

    fun updateBlockedState(oldState: Int, newState: Int) {
        when (oldState) {
            -1 -> {}
            RetainedFragment.blockedStateLoading -> findViewById<View>(R.id.loading_text).visibility = View.GONE
            RetainedFragment.blockedStateLoaded -> findViewById<View>(R.id.list).visibility = View.GONE
            RetainedFragment.blockedStateFailed -> findViewById<View>(R.id.label_error).visibility = View.GONE
        }

        when (newState) {
            RetainedFragment.blockedStateLoading -> findViewById<View>(R.id.loading_text).visibility = View.VISIBLE
            RetainedFragment.blockedStateLoaded -> findViewById<View>(R.id.list).visibility = View.VISIBLE
            RetainedFragment.blockedStateFailed -> findViewById<View>(R.id.label_error).visibility = View.VISIBLE
        }
    }

    fun updateBlockedList() {
        blockedListAdapter.notifyDataSetChanged()
    }

    fun removeBlockedAddress(normalizedAddress: String) {
        //Removing the first matching instance
        val iterator: MutableIterator<BlockedAddress> = retainedFragment.blockedList.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().normalizedAddress == normalizedAddress) {
                iterator.remove()
                break
            }
        }
    }

    data class BlockedAddress(val address: String, val normalizedAddress: String, val blockCount: Int)

    private inner class ListAdapter(context: Context, resource: Int, items: ArrayList<BlockedAddress>) : ArrayAdapter<BlockedAddress>(context, resource, items) {
        override fun getView(position: Int, convertView: View?, @NonNull parent: ViewGroup): View {
            //Inflating the view if one wasn't provided
            var view = convertView
            if (view == null) view = layoutInflater.inflate(R.layout.activity_blockedaddresses_listitem, null)

            //Getting the item
            val blockedAddress = getItem(position)

            //Checking if the item is valid
            if (blockedAddress != null) {
                //Filling in the view text
                (view!!.findViewById<View>(R.id.label_address) as TextView).text = blockedAddress.address
                (view.findViewById<View>(R.id.label_count) as TextView).text = resources.getQuantityString(R.plurals.message_blockedmessagecount, blockedAddress.blockCount, blockedAddress.blockCount)

                //Setting the listeners
                view.findViewById<View>(R.id.button_remove).setOnClickListener {
                    //Showing a dialog
                    MaterialAlertDialogBuilder(this@BlockedAddresses)
                        .setMessage(R.string.message_confirm_unblock)
                        .setPositiveButton(R.string.action_unblock) { _, _ -> removeBlockedAddress(blockedAddress.normalizedAddress) }
                        .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
                        .create().show()
                }
            }

            //Returning the view
            return view!!
        }
    }

    class RetainedFragment : Fragment() {
        //Creating the task values
        private var parentActivity: BlockedAddresses? = null

        //Creating the state values
        private var blockedState = blockedStateIdle

        //Creating the other values
        private val originalBlockedList = ArrayList<BlockedAddress>()
        val blockedList = ArrayList<BlockedAddress>()

        /**
         * Hold a reference to the parent Activity so we can report the
         * task's current progress and results. The Android framework
         * will pass us a reference to the newly created Activity after
         * each configuration change.
         */
        override fun onAttach(context: Context) {
            //Calling the super method
            super.onAttach(context)

            //Getting the parent activity
            parentActivity = context as BlockedAddresses
        }

        /**
         * This method will only be called once when the retained
         * Fragment is first created.
         */
        // ... inside the RetainedFragment class
        override fun onCreate(savedInstanceState: Bundle?) {
            //Calling the super method
            super.onCreate(savedInstanceState)

            //Retain this fragment across configuration changes
            setRetainInstance(true)
        }

        /**
         * Set the callback to null so we don't accidentally leak the
         * Activity instance.
         */
        override fun onDetach() {
            super.onDetach()
            parentActivity = null
        }

// ...

        fun updateState(state: Int) {
            //Returning if the requested state matches the existing state
            if (blockedState == state) return

            //Updating the activity
            if (parentActivity != null) parentActivity!!.updateBlockedState(blockedState, state)

            //Setting the new state
            blockedState = state
        }

        fun loadBlocked(appContext: Context) {
            //Returning if the state is not ready to load blocked users
            if (blockedState == blockedStateLoading || blockedState == blockedStateLoaded) return

            //Starting the task
            LoadBlockedAsyncTask(appContext, this).execute()

            //Setting the state
            updateState(blockedStateLoaded)
        }

        class LoadBlockedAsyncTask(context: Context, fragment: RetainedFragment) : AsyncTask<Void?, Void?, ArrayList<BlockedAddress>?>() {
            //Creating the reference values
            private val contextReference: WeakReference<Context>
            private val fragmentReference: WeakReference<RetainedFragment>

            init {
                //Setting the references
                contextReference = WeakReference(context)
                fragmentReference = WeakReference(fragment)
            }

            override fun doInBackground(vararg parameters: Void?): ArrayList<BlockedAddress>? {
                //Getting the context
                val context = contextReference.get() ?: return null

                //Querying the database
                //return DatabaseManager.fetchBlockedAddresses(DatabaseManager.getReadableDatabase(context));
                return null
            }

            override fun onPostExecute(blocked: ArrayList<BlockedAddress>?) {
                //Getting the fragment
                val fragment = fragmentReference.get() ?: return

                //Checking if the result is invalid
                if (blocked == null) {
                    //Updating to a failed state
                    fragment.updateState(blockedStateFailed)
                } else {
                    //Saving the data
                    fragment.blockedList.addAll(blocked)
                    fragment.originalBlockedList.addAll(fragment.blockedList)

                    //Updating the list
                    if (fragment.activity != null) fragment.parentActivity!!.updateBlockedList()

                    //Updating to a completed state
                    fragment.updateState(blockedStateLoaded)
                }
            }
        }

        companion object {
            //Creating the state values
            const val blockedStateIdle = 0
            const val blockedStateLoading = 1
            const val blockedStateLoaded = 2
            const val blockedStateFailed = 3

            //Creating the retained fragment values
            private val retainedFragmentTag = RetainedFragment::class.java.name
        }
    }

    companion object {
        private val retainedFragmentTag = RetainedFragment::class.java.name
    }
}