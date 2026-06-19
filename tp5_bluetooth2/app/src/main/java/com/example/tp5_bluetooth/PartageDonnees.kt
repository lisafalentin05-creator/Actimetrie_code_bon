package com.example.tp5_bluetooth

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedViewModel : ViewModel() {
    val codeBarre = MutableLiveData<String>()
    val listeAppareils = MutableLiveData<List<String>>()
}