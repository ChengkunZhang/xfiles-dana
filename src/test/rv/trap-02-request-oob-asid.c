#include "src/main/c/xfiles-user.h"

int main() {
  pk_syscall_set_asid(2);

  asid_nnid_table * ant;
  asid_nnid_table_create(&ant, 2, 4);
  pk_syscall_set_antp(ant);

  tid_type tid = new_write_request(0, 0, 0);
  element_type junk = 0;
  write_data(tid, &junk, 1);
  while(1) {};
}
