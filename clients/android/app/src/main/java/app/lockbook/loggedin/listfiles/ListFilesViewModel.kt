package app.lockbook.loggedin.listfiles

import android.app.Activity.RESULT_CANCELED
import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import app.lockbook.R
import app.lockbook.utils.*
import app.lockbook.utils.ClickInterface
import app.lockbook.utils.RequestResultCodes.DELETE_RESULT_CODE
import app.lockbook.utils.RequestResultCodes.POP_UP_INFO_REQUEST_CODE
import app.lockbook.utils.RequestResultCodes.RENAME_RESULT_CODE
import app.lockbook.utils.SharedPreferences.SORT_FILES_A_Z
import app.lockbook.utils.SharedPreferences.SORT_FILES_FIRST_CHANGED
import app.lockbook.utils.SharedPreferences.SORT_FILES_KEY
import app.lockbook.utils.SharedPreferences.SORT_FILES_LAST_CHANGED
import app.lockbook.utils.SharedPreferences.SORT_FILES_TYPE
import app.lockbook.utils.SharedPreferences.SORT_FILES_Z_A
import com.beust.klaxon.Klaxon
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.*
import timber.log.Timber

class ListFilesViewModel(path: String, application: Application) :
    AndroidViewModel(application),
    ClickInterface {

    private var job = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + job)
    private val coreModel = CoreModel(Config(path))
    private lateinit var fileCreationType: FileType

    private val _files = MutableLiveData<List<FileMetadata>>()
    private val _navigateToFileEditor = MutableLiveData<EditableFile>()
    private val _navigateToPopUpInfo = MutableLiveData<FileMetadata>()
    private val _listFilesRefreshing = MutableLiveData<Boolean>()
    private val _collapseExpandFAB = MutableLiveData<Unit>()
    private val _createFileNameDialog = MutableLiveData<Unit>()
    private val _errorHasOccurred = MutableLiveData<String>()

    val files: LiveData<List<FileMetadata>>
        get() = _files

    val navigateToFileEditor: LiveData<EditableFile>
        get() = _navigateToFileEditor

    val navigateToPopUpInfo: LiveData<FileMetadata>
        get() = _navigateToPopUpInfo

    val listFilesRefreshing: LiveData<Boolean>
        get() = _listFilesRefreshing

    val collapseExpandFAB: LiveData<Unit>
        get() = _collapseExpandFAB

    val createFileNameDialog: LiveData<Unit>
        get() = _createFileNameDialog

    val errorHasOccurred: LiveData<String>
        get() = _errorHasOccurred

    fun startUpFiles() {
        uiScope.launch {
            withContext(Dispatchers.IO) {
                sync()
                startUpInRoot()
            }
        }
    }

    fun quitOrNot(): Boolean {
        if (coreModel.parentFileMetadata.id == coreModel.parentFileMetadata.parent) {
            return false
        }
        upADirectory()

        return true
    }

    private fun upADirectory() {
        when (val getSiblingsOfParentResult = coreModel.getSiblingsOfParent()) {
            is Ok -> {
                when (val getParentOfParentResult = coreModel.getParentOfParent()) {
                    is Ok -> matchToDefaultSortOption(getSiblingsOfParentResult.value)
                    is Err -> when (val error = getParentOfParentResult.error) {
                        is GetFileByIdError.NoFileWithThatId -> _errorHasOccurred.postValue("Error! No file with that id!")
                        is GetFileByIdError.UnexpectedError -> {
                            Timber.e("Unable to get the parent of the current path: ${error.error}")
                            _errorHasOccurred.postValue(
                                UNEXPECTED_ERROR_OCCURRED
                            )
                        }
                        else -> {
                            Timber.e("GetFileByIdError not matched: ${error::class.simpleName}.")
                            _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                        }
                    }
                }
            }
            is Err -> when(val error = getSiblingsOfParentResult.error) {
                is GetChildrenError.UnexpectedError -> {
                    Timber.e("Unable to get siblings of the parent: ${error.error}")
                    _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                }
                else -> {
                    Timber.e("GetChildrenError not matched: ${error::class.simpleName}.")
                    _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                }
            }
        }
    }

    private fun refreshFiles() {
        when (val getChildrenResult = coreModel.getChildrenOfParent()) {
            is Ok -> {
                matchToDefaultSortOption(getChildrenResult.value)
            }
            is Err -> when(val error = getChildrenResult.error) {
                is GetChildrenError.UnexpectedError -> {
                    Timber.e("Unable to get children: ${getChildrenResult.error}")
                    _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                }
                else -> {
                    Timber.e("GetChildrenError not matched: ${error::class.simpleName}.")
                    _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                }
            }
        }
    }

    private fun createInsertRefreshFiles(name: String, fileType: String) {
        when (val createFileResult = coreModel.createFile(name, fileType)) {
            is Ok -> {
                val insertFileResult = coreModel.insertFile(createFileResult.value)
                if (insertFileResult is Err) {
                    when(val error = insertFileResult.error) {
                        is InsertFileError.UnexpectedError -> {
                            Timber.e("Unable to insert a newly created file: ${insertFileResult.error}")
                            _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                        }
                        else -> {
                            Timber.e("InsertFileError not matched: ${error::class.simpleName}.")
                            _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                        }
                    }
                }

                refreshFiles()
            }
            is Err -> when (val error = createFileResult.error) {
                is CreateFileError.NoAccount -> _errorHasOccurred.postValue("Error! No account!")
                is CreateFileError.DocumentTreatedAsFolder -> _errorHasOccurred.postValue("Error! Document is treated as folder!")
                is CreateFileError.CouldNotFindAParent -> _errorHasOccurred.postValue("Error! Could not find file parent!")
                is CreateFileError.FileNameNotAvailable -> _errorHasOccurred.postValue("Error! File name not available!")
                is CreateFileError.FileNameContainsSlash -> _errorHasOccurred.postValue("Error! File contains a slash!")
                is CreateFileError.UnexpectedError -> {
                    Timber.e("Unable to create a file: ${error.error}")
                    _errorHasOccurred.postValue(
                        UNEXPECTED_ERROR_OCCURRED
                    )
                }
                else -> {
                    Timber.e("CreateFileError not matched: ${error::class.simpleName}.")
                    _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                }
            }
        }
    }

    private fun renameRefreshFiles(id: String, newName: String) {
        when (val renameFileResult = coreModel.renameFile(id, newName)) {
            is Ok -> refreshFiles()
            is Err -> when (val error = renameFileResult.error) {
                is RenameFileError.FileDoesNotExist -> _errorHasOccurred.postValue("Error! File does not exist!")
                is RenameFileError.NewNameContainsSlash -> _errorHasOccurred.postValue("Error! New name contains slash!")
                is RenameFileError.FileNameNotAvailable -> _errorHasOccurred.postValue("Error! File name not available!")
                is RenameFileError.UnexpectedError -> {
                    Timber.e("Unable to rename file: ${error.error}")
                    _errorHasOccurred.postValue(
                        UNEXPECTED_ERROR_OCCURRED
                    )
                }
                else -> {
                    Timber.e("RenameFileError not matched: ${error::class.simpleName}.")
                    _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                }
            }
        }
    }

    private fun deleteRefreshFiles(id: String) {
        when (val deleteFileResult = coreModel.deleteFile(id)) {
            is Ok -> refreshFiles()
            is Err -> when (val error = deleteFileResult.error) {
                is DeleteFileError.NoFileWithThatId -> _errorHasOccurred.postValue("Error! No file with that id!")
                is DeleteFileError.UnexpectedError -> {
                    Timber.e("Unable to delete file: ${error.error}")
                    _errorHasOccurred.postValue(
                        UNEXPECTED_ERROR_OCCURRED
                    )
                }
                else -> {
                    Timber.e("DeleteFileError not matched: ${error::class.simpleName}.")
                    _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                }
            }
        }
    }

    private fun matchToDefaultSortOption(files: List<FileMetadata>) {
        when (val optionValue = PreferenceManager.getDefaultSharedPreferences(getApplication()).getString(SORT_FILES_KEY, SORT_FILES_A_Z)) {
            SORT_FILES_A_Z -> sortFilesAlpha(files, false)
            SORT_FILES_Z_A -> sortFilesAlpha(files, true)
            SORT_FILES_LAST_CHANGED -> sortFilesChanged(files, false)
            SORT_FILES_FIRST_CHANGED -> sortFilesChanged(files, true)
            SORT_FILES_TYPE -> sortFilesType(files)
            else -> {
                Timber.e("File sorting shared preference does not match every supposed option: $optionValue")
                _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
            }
        }
    }

    private fun sortFilesAlpha(files: List<FileMetadata>, inReverse: Boolean) {
        if (inReverse) {
            _files.postValue(
                files.sortedByDescending { fileMetadata ->
                    fileMetadata.name
                }
            )
        } else {
            _files.postValue(
                files.sortedBy { fileMetadata ->
                    fileMetadata.name
                }
            )
        }
    }

    private fun sortFilesChanged(files: List<FileMetadata>, inReverse: Boolean) {
        if (inReverse) {
            _files.postValue(
                files.sortedByDescending { fileMetadata ->
                    fileMetadata.metadata_version
                }
            )
        } else {
            _files.postValue(
                files.sortedBy { fileMetadata ->
                    fileMetadata.metadata_version
                }
            )
        }
    }

    private fun sortFilesType(files: List<FileMetadata>) {
        val tempFolders = files.filter { fileMetadata ->
            fileMetadata.file_type.name == FileType.Folder.name
        }
        val tempDocuments = files.filter { fileMetadata ->
            fileMetadata.file_type.name == FileType.Document.name
        }
        _files.postValue(
            tempFolders.union(
                tempDocuments.sortedWith(
                    compareBy(
                        { fileMetadata ->
                            Regex(".[^.]+\$").find(fileMetadata.name)?.value
                        },
                        { fileMetaData ->
                            fileMetaData.name
                        }
                    )
                )
            ).toList()
        )
    }

    private fun handleReadDocument(fileMetadata: FileMetadata) {
        when (val documentResult = coreModel.getDocumentContent(fileMetadata.id)) {
            is Ok -> {
                _navigateToFileEditor.postValue(
                    EditableFile(
                        fileMetadata.name,
                        fileMetadata.id,
                        documentResult.value
                    )
                )
                coreModel.lastDocumentAccessed = fileMetadata
            }
            is Err -> when (val error = documentResult.error) {
                is ReadDocumentError.TreatedFolderAsDocument -> _errorHasOccurred.postValue("Error! Folder treated as document!")
                is ReadDocumentError.NoAccount -> _errorHasOccurred.postValue("Error! No account!")
                is ReadDocumentError.FileDoesNotExist -> _errorHasOccurred.postValue("Error! File does not exist!")
                is ReadDocumentError.UnexpectedError -> {
                    Timber.e("Unable to get content of file: ${error.error}")
                    _errorHasOccurred.postValue(
                        UNEXPECTED_ERROR_OCCURRED
                    )
                }
                else -> {
                    Timber.e("ReadDocumentError not matched: ${error::class.simpleName}.")
                    _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                }
            }
        }
    }

    private fun intoFolder(fileMetadata: FileMetadata) {
        coreModel.parentFileMetadata = fileMetadata
        refreshFiles()
    }

    private fun sync() {
        val syncAllResult = coreModel.syncAllFiles()
        if (syncAllResult is Err) {
            when (val error = syncAllResult.error) {
                is SyncAllError.NoAccount -> _errorHasOccurred.postValue("Error! No account!")
                is SyncAllError.CouldNotReachServer -> _errorHasOccurred.postValue("Error! Could not reach server!")
                is SyncAllError.ExecuteWorkError -> { // more will be done about this since it can send a wide variety of errors
                    _errorHasOccurred.postValue("Unable to sync work.")
                }
                is SyncAllError.UnexpectedError -> {
                    Timber.e("Unable to sync all files: ${error.error}")
                    _errorHasOccurred.postValue(
                        UNEXPECTED_ERROR_OCCURRED
                    )
                }
                else -> {
                    Timber.e("SyncAllError not matched: ${error::class.simpleName}.")
                    _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                }
            }
        }
    }

    private fun startUpInRoot() {
        when (val result = coreModel.setParentToRoot()) {
            is Ok -> refreshFiles()
            is Err -> when (val error = result.error) {
                is GetRootError.NoRoot -> _errorHasOccurred.postValue("No root!")
                is GetRootError.UnexpectedError -> {
                    Timber.e("Unable to set parent to root: ${error.error}")
                    _errorHasOccurred.postValue(
                        UNEXPECTED_ERROR_OCCURRED
                    )
                }
                else -> {
                    Timber.e("GetRootError not matched: ${error::class.simpleName}.")
                    _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                }
            }
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        uiScope.launch {
            withContext(Dispatchers.IO) {
                if (data is Intent) {
                    when (requestCode) {
                        POP_UP_INFO_REQUEST_CODE -> handlePopUpInfoRequest(resultCode, data)
                        else -> {
                            Timber.e("Request code not matched: $requestCode")
                        }
                    }
                } else if (resultCode != RESULT_CANCELED) {
                    Timber.e("Unable to recognize resultCode.")
                    _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                }
            }
        }
    }

    fun handleNewFileRequest(name: String) {
        createInsertRefreshFiles(name, Klaxon().toJsonString(fileCreationType))
    }

    private fun handlePopUpInfoRequest(resultCode: Int, data: Intent) {
        val id = data.getStringExtra("id")
        if (id is String) {
            when (resultCode) {
                RENAME_RESULT_CODE -> {
                    val newName = data.getStringExtra("new_name")
                    if (newName != null) {
                        renameRefreshFiles(id, newName)
                    } else {
                        Timber.e("newName is null.")
                        _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                    }
                }
                DELETE_RESULT_CODE -> deleteRefreshFiles(id)
                else -> {
                    Timber.e("Result code not matched: $resultCode")
                    _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                }
            }
        } else {
            Timber.e("id is null.")
            _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
        }
    }

    fun syncRefresh() {
        uiScope.launch {
            withContext(Dispatchers.IO) {
                sync()
                refreshFiles()
                _listFilesRefreshing.postValue(false)
            }
        }
    }

    fun onNewDocumentFABClicked() {
        fileCreationType = FileType.Document
        _collapseExpandFAB.postValue(Unit)
        _createFileNameDialog.postValue(Unit)
    }

    fun onNewFolderFABClicked() {
        fileCreationType = FileType.Folder
        _collapseExpandFAB.postValue(Unit)
        _createFileNameDialog.postValue(Unit)
    }

    fun collapseFAB() {
        _collapseExpandFAB.postValue(Unit)
    }

    fun onSortPressed(id: Int) {
        uiScope.launch {
            withContext(Dispatchers.IO) {
                val pref = PreferenceManager.getDefaultSharedPreferences(getApplication()).edit()
                when (id) {
                    R.id.menu_list_files_sort_last_changed -> pref.putString(SORT_FILES_KEY, SORT_FILES_LAST_CHANGED).apply()
                    R.id.menu_list_files_sort_a_z -> pref.putString(SORT_FILES_KEY, SORT_FILES_A_Z).apply()
                    R.id.menu_list_files_sort_z_a -> pref.putString(SORT_FILES_KEY, SORT_FILES_Z_A).apply()
                    R.id.menu_list_files_sort_first_changed -> pref.putString(SORT_FILES_KEY, SORT_FILES_FIRST_CHANGED).apply()
                    R.id.menu_list_files_sort_type -> pref.putString(SORT_FILES_KEY, SORT_FILES_TYPE).apply()
                    else -> {
                        Timber.e("Unrecognized sort item id.")
                        _errorHasOccurred.postValue(UNEXPECTED_ERROR_OCCURRED)
                    }
                }

                val files = _files.value
                if (files is List<FileMetadata>) {
                    matchToDefaultSortOption(files)
                } else {
                    _errorHasOccurred.postValue("Unable to retrieve files from LiveData.")
                }
            }
        }
    }

    override fun onItemClick(position: Int) {
        uiScope.launch {
            withContext(Dispatchers.IO) {
                _files.value?.let {
                    val fileMetadata = it[position]

                    if (fileMetadata.file_type == FileType.Folder) {
                        intoFolder(fileMetadata)
                    } else {
                        handleReadDocument(fileMetadata)
                    }
                }
            }
        }
    }

    override fun onLongClick(position: Int) {
        uiScope.launch {
            withContext(Dispatchers.IO) {
                _files.value?.let {
                    _navigateToPopUpInfo.postValue(it[position])
                }
            }
        }
    }
}
