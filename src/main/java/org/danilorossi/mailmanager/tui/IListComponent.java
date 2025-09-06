package org.danilorossi.mailmanager.tui;

public interface IListComponent<T> {

  IListComponent<T> build();

  void editSelectedItemAction();

  void delSelectedItemAction();

  void refreshTable();

  T openItemDialog(T existing);

  void addItemAction();

  void focus();
}
